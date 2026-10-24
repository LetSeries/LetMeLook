package com.letseries.letmelook.client.render;

import com.letseries.letmelook.client.timeline.ReplayTimeline;
import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Ghost renderer: translucent historical-state overlay.
 *
 * <p>Design:
 * <ul>
 *   <li>All vertices go through {@code consumers().getBuffer(...)} with
 *       coordinates <b>relative to the camera</b> (Fabric docs requirement);
 *       the {@link MatrixStack} from the render event is NOT translated, the
 *       per-box offset is baked into the vertex positions.</li>
 *   <li>REPLAY/ROLLBACK fold the visible events into a last-writer-wins world
 *       reconstruction (one box per position that still has a block), so
 *       overlapping edits no longer stack boxes. DIFF renders the raw window.</li>
 *   <li>ROLLBACK additionally drops positions whose live client-side block
 *       already matches the reconstructed state — what remains is exactly what a
 *       rollback to the playhead would change.</li>
 *   <li>Cap: at most {@link #MAX_BOXES} boxes per frame, chosen nearest-first,
 *       refreshed only when the candidate set changes or the camera moves.</li>
 * </ul>
 */
public final class GhostLayer {
    /** Max ghost boxes drawn per frame (perf guard). */
    public static final int MAX_BOXES = 4096;

    /** Recompute the nearest-box selection once the camera has moved this far (blocks²). */
    private static final double NEAREST_REFRESH_DIST_SQ = 16.0;

    /** Bounded cache of parsed state strings (registry lookup + property parse). */
    private static final int STATE_CACHE_CAP = 512;
    private static final Map<String, BlockState> STATE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private final ReplayTimeline timeline = new ReplayTimeline();
    private boolean visible = true;

    // Render caches -------------------------------------------------------
    private long cacheKey = Long.MIN_VALUE;
    private List<BlockEvent> candidates = List.of();
    private List<BlockEvent> nearest = List.of();
    private double nearestCamX;
    private double nearestCamY;
    private double nearestCamZ;
    private boolean hasNearest;

    public ReplayTimeline timeline() {
        return timeline;
    }

    public void ingest(TimelineChunk chunk) {
        timeline.ingest(chunk);
    }

    public int eventCount() {
        return timeline.eventCount();
    }

    public int expectedTotal() {
        return timeline.expectedTotal();
    }

    public boolean isComplete() {
        return timeline.isComplete();
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void toggleVisible() {
        this.visible = !this.visible;
    }

    public void clear() {
        timeline.clear();
        invalidate();
    }

    private void invalidate() {
        cacheKey = Long.MIN_VALUE;
        candidates = List.of();
        nearest = List.of();
        hasNearest = false;
    }

    public static void register(GhostLayer layer) {
        // Fill boxes before translucent terrain so depth still occludes ghosts
        // behind real blocks; outlines ride the debug pass on top.
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ctx -> layer.renderFill(ctx.matrices(), ctx.consumers(),
                ctx.worldState().cameraRenderState.pos));
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ctx -> layer.renderOutlines(ctx.matrices(),
                ctx.consumers(), ctx.worldState().cameraRenderState.pos));
    }

    private void renderFill(MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider consumers,
            Vec3d cameraPos) {
        if (!visible) {
            return;
        }
        List<BlockEvent> events = renderList(cameraPos);
        if (events.isEmpty()) {
            return;
        }
        VertexConsumer buffer = consumers.getBuffer(RenderLayers.debugFilledBox());
        var entry = matrices.peek();
        boolean rollback = timeline.mode() == ReplayTimeline.Mode.ROLLBACK;
        MinecraftClient mc = MinecraftClient.getInstance();
        for (BlockEvent e : events) {
            if (e.action == 0 && "minecraft:air".equals(e.to)) {
                continue; // broken-away blocks have no ghost volume
            }
            if (rollback && matchesLiveWorld(mc, e)) {
                continue; // already in the reconstructed state: nothing to roll back
            }
            float x = e.x - (float) cameraPos.getX();
            float y = e.y - (float) cameraPos.getY();
            float z = e.z - (float) cameraPos.getZ();
            quad(buffer, entry, x, y, z, x + 1, y + 1, z + 1, ghostColor(e, rollback, 0.22f));
        }
    }

    private void renderOutlines(MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider consumers,
            Vec3d cameraPos) {
        if (!visible) {
            return;
        }
        List<BlockEvent> events = renderList(cameraPos);
        if (events.isEmpty()) {
            return;
        }
        VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
        boolean rollback = timeline.mode() == ReplayTimeline.Mode.ROLLBACK;
        MinecraftClient mc = MinecraftClient.getInstance();
        for (BlockEvent e : events) {
            if (rollback && matchesLiveWorld(mc, e)) {
                continue;
            }
            double x = e.x - cameraPos.getX();
            double y = e.y - cameraPos.getY();
            double z = e.z - cameraPos.getZ();
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(), x, y, z,
                    ghostColor(e, rollback, 1.0f), 0.02f);
        }
    }

    /** True when the live client world already matches the reconstructed state. */
    private static boolean matchesLiveWorld(MinecraftClient mc, BlockEvent e) {
        if (mc == null || mc.world == null) {
            return false;
        }
        BlockState live = mc.world.getBlockState(new BlockPos(e.x, e.y, e.z));
        return live.equals(parseState(e.to));
    }

    // ------------------------------------------------------------------
    // Candidate selection
    // ------------------------------------------------------------------

    /**
     * The events to consider this frame: mode-filtered, last-writer-wins folded
     * for REPLAY/ROLLBACK, capped to the {@link #MAX_BOXES} nearest boxes.
     */
    private List<BlockEvent> renderList(Vec3d cameraPos) {
        List<BlockEvent> all = candidateEvents();
        if (all.isEmpty()) {
            return all;
        }
        if (all.size() <= MAX_BOXES) {
            return all;
        }
        double dx = cameraPos.getX() - nearestCamX;
        double dy = cameraPos.getY() - nearestCamY;
        double dz = cameraPos.getZ() - nearestCamZ;
        boolean moved = dx * dx + dy * dy + dz * dz > NEAREST_REFRESH_DIST_SQ;
        if (hasNearest && !moved) {
            return nearest;
        }
        List<BlockEvent> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(e -> distanceSq(e, cameraPos)));
        nearest = new ArrayList<>(sorted.subList(0, MAX_BOXES));
        nearestCamX = cameraPos.getX();
        nearestCamY = cameraPos.getY();
        nearestCamZ = cameraPos.getZ();
        hasNearest = true;
        return nearest;
    }

    /** Mode-filtered events, folded to one event per position for REPLAY/ROLLBACK. */
    private List<BlockEvent> candidateEvents() {
        long key = cacheKey();
        if (key == cacheKey) {
            return candidates;
        }
        List<BlockEvent> visibleEvents = timeline.visibleAtPlayhead();
        List<BlockEvent> folded;
        if (timeline.mode() == ReplayTimeline.Mode.DIFF) {
            folded = visibleEvents;
        } else {
            folded = new ArrayList<>(reconstructAt(visibleEvents, timeline.playhead()).values());
        }
        candidates = folded;
        cacheKey = key;
        nearest = List.of();
        hasNearest = false;
        return candidates;
    }

    private long cacheKey() {
        long key = timeline.revision();
        key = key * 31 + timeline.playhead();
        key = key * 31 + timeline.mode().ordinal();
        key = key * 31 + timeline.diffWindowMs();
        return key;
    }

    private static double distanceSq(BlockEvent e, Vec3d cam) {
        double dx = e.x + 0.5 - cam.getX();
        double dy = e.y + 0.5 - cam.getY();
        double dz = e.z + 0.5 - cam.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Place = green, break = red, rollback preview = amber. */
    static int ghostColor(BlockEvent e, boolean rollback, float alpha) {
        float r;
        float g;
        float b;
        if (rollback) {
            r = 1.0f;
            g = 0.75f;
            b = 0.2f;
        } else if (e.action == 0) {
            r = 1.0f;
            g = 0.25f;
            b = 0.25f;
        } else {
            r = 0.35f;
            g = 1.0f;
            b = 0.45f;
        }
        return ColorHelper.fromFloats(alpha, r, g, b);
    }

    /**
     * Axis-aligned unit box as 6 quads, mirroring vanilla's gizmo quad writer:
     * {@code vertex(entry, x,y,z).color(argb)} per corner.
     */
    private static void quad(VertexConsumer buffer, MatrixStack.Entry entry,
            float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        // -Y (bottom)
        v(buffer, entry, x0, y0, z0, color);
        v(buffer, entry, x1, y0, z0, color);
        v(buffer, entry, x1, y0, z1, color);
        v(buffer, entry, x0, y0, z1, color);
        // +Y (top)
        v(buffer, entry, x0, y1, z1, color);
        v(buffer, entry, x1, y1, z1, color);
        v(buffer, entry, x1, y1, z0, color);
        v(buffer, entry, x0, y1, z0, color);
        // -Z (north)
        v(buffer, entry, x0, y0, z0, color);
        v(buffer, entry, x0, y1, z0, color);
        v(buffer, entry, x1, y1, z0, color);
        v(buffer, entry, x1, y0, z0, color);
        // +Z (south)
        v(buffer, entry, x1, y0, z1, color);
        v(buffer, entry, x1, y1, z1, color);
        v(buffer, entry, x0, y1, z1, color);
        v(buffer, entry, x0, y0, z1, color);
        // -X (west)
        v(buffer, entry, x0, y0, z1, color);
        v(buffer, entry, x0, y1, z1, color);
        v(buffer, entry, x0, y1, z0, color);
        v(buffer, entry, x0, y0, z0, color);
        // +X (east)
        v(buffer, entry, x1, y0, z0, color);
        v(buffer, entry, x1, y1, z0, color);
        v(buffer, entry, x1, y1, z1, color);
        v(buffer, entry, x1, y0, z1, color);
    }

    private static void v(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y, float z, int color) {
        buffer.vertex(entry, x, y, z).color(color);
    }

    // ------------------------------------------------------------------
    // BlockState parsing: "minecraft:oak_stairs[facing=north]" -> BlockState.
    // Used by the ghost renderer, the rollback preview and the exporter to
    // resolve historical states against the client's registry.
    // ------------------------------------------------------------------

    /**
     * Resolve a state string against the client registry. Unknown ids fall back
     * to the closest known block (namespace-tolerant), then to air.
     *
     * <p>Results are memoised (bounded) because the rollback pass resolves up to
     * {@link #MAX_BOXES} state strings per frame.
     */
    public static BlockState parseState(String s) {
        if (s == null || s.isEmpty()) {
            return Blocks.AIR.getDefaultState();
        }
        BlockState cached = STATE_CACHE.get(s);
        if (cached != null) {
            return cached;
        }
        BlockState state = parseStateUncached(s);
        if (STATE_CACHE.size() >= STATE_CACHE_CAP) {
            STATE_CACHE.clear();
        }
        STATE_CACHE.put(s, state);
        return state;
    }

    private static BlockState parseStateUncached(String s) {
        String id = s;
        String props = "";
        int lb = s.indexOf('[');
        if (lb >= 0 && s.endsWith("]")) {
            id = s.substring(0, lb);
            props = s.substring(lb + 1, s.length() - 1);
        }
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) {
            return Blocks.AIR.getDefaultState();
        }
        Block block = Registries.BLOCK.get(ident);
        if (block == null || block == Blocks.AIR && !"minecraft:air".equals(id)) {
            // namespace-tolerant retry ("stone" -> "minecraft:stone")
            Identifier retry = Identifier.tryParse("minecraft:" + ident.getPath());
            if (retry != null) {
                Block b2 = Registries.BLOCK.get(retry);
                if (b2 != null) {
                    block = b2;
                }
            }
        }
        if (block == null) {
            return Blocks.AIR.getDefaultState();
        }
        BlockState state = block.getDefaultState();
        if (!props.isEmpty()) {
            state = applyProps(state, props);
        }
        return state;
    }

    private static BlockState applyProps(BlockState state, String props) {
        Block block = state.getBlock();
        for (String kv : props.split(",")) {
            String[] parts = kv.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = parts[0].trim();
            String val = parts[1].trim();
            for (var prop : block.getStateManager().getProperties()) {
                if (!prop.getName().equals(key)) {
                    continue;
                }
                state = setProp(state, prop, val);
                break;
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setProp(BlockState state, net.minecraft.state.property.Property prop, String val) {
        try {
            var parsed = prop.parse(val);
            if (parsed.isPresent()) {
                return state.with(prop, (Comparable) parsed.get());
            }
        } catch (Exception ignored) {
            // keep default
        }
        return state;
    }

    /**
     * Reconstruct the world state at the playhead for a region: later events
     * overwrite earlier ones per position (last-writer-wins). Returned map key
     * is the packed {@code BlockPos#asLong}.
     *
     * <p>Requires {@code events} to be time-sorted (as {@link ReplayTimeline}
     * keeps them); iteration stops at the first event newer than the playhead.
     */
    public static Map<Long, BlockEvent> reconstructAt(List<BlockEvent> events, long playheadMs) {
        Map<Long, BlockEvent> out = new LinkedHashMap<>();
        for (BlockEvent e : events) {
            if (e.t > playheadMs) {
                break;
            }
            out.put(BlockPos.asLong(e.x, e.y, e.z), e);
        }
        return out;
    }

    /** Human summary for /lm status. */
    public String summary() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String dim = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "?";
        StringBuilder sb = new StringBuilder();
        sb.append("events=").append(eventCount()).append('/').append(expectedTotal())
                .append(" dim=").append(dim)
                .append(" mode=").append(timeline.mode().name().toLowerCase(Locale.ROOT))
                .append(timeline.mode() == ReplayTimeline.Mode.DIFF
                        ? "(" + (long) timeline.diffWindowSeconds() + "s)" : "")
                .append(" visible=").append(visible)
                .append(" complete=").append(isComplete());
        if (timeline.truncated()) {
            sb.append(" TRUNCATED");
        }
        return sb.toString();
    }

    /**
     * Snapshot of the events the renderer would consider (for the exporter / UI).
     * Unaffected by the per-frame nearest-box cap.
     */
    public List<BlockEvent> visibleSnapshot() {
        return new ArrayList<>(candidateEvents());
    }
}
