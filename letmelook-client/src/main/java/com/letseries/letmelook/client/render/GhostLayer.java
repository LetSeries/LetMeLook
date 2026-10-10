package com.letseries.letmelook.client.render;

import com.letseries.letmelook.client.timeline.ReplayTimeline;
import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
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
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/**
 * W2 ghost renderer: translucent historical-state overlay.
 *
 * <p>Design (verified against 1.21.11 mappings):
 * <ul>
 *   <li>All vertices go through {@code consumers().getBuffer(...)} with
 *       coordinates <b>relative to the camera</b> (Fabric docs requirement);
 *       the {@link MatrixStack} from the render event is NOT translated, the
 *       per-box offset is baked into the vertex positions.</li>
 *   <li>History skin: the historical {@code to} BlockState is submitted via
 *       {@code commandQueue().submitBlock(...)} — wait, no: submitBlock draws
 *       the full opaque model, which would hide the real world. Instead we draw
 *       two debug passes: a translucent fill ({@code debugFilledBox}) plus a
 *       colored outline ({@code lines}), tinted by action (place=green,
 *       break=red) or white in ROLLBACK preview.</li>
 *   <li>Cap: at most {@link #MAX_BOXES} boxes per frame, oldest-first, so a
 *       50k-event timeline can't freeze the renderer.</li>
 * </ul>
 */
public final class GhostLayer {
    /** Max ghost boxes drawn per frame (perf guard). */
    public static final int MAX_BOXES = 4096;

    private final ReplayTimeline timeline = new ReplayTimeline();
    private boolean visible = true;

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

    public void clear() {
        timeline.clear();
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
        if (!visible || timeline.eventCount() == 0) {
            return;
        }
        List<BlockEvent> events = timeline.visibleAtPlayhead();
        if (events.isEmpty()) {
            return;
        }
        VertexConsumer buffer = consumers.getBuffer(RenderLayers.debugFilledBox());
        var entry = matrices.peek();
        int drawn = 0;
        boolean rollback = timeline.mode() == ReplayTimeline.Mode.ROLLBACK;
        for (int i = events.size() - 1; i >= 0 && drawn < MAX_BOXES; i--) {
            BlockEvent e = events.get(i);
            if (e.action == 0 && "minecraft:air".equals(e.to)) {
                continue; // broken-away blocks have no ghost volume
            }
            float x = e.x - (float) cameraPos.getX();
            float y = e.y - (float) cameraPos.getY();
            float z = e.z - (float) cameraPos.getZ();
            int color = ghostColor(e, rollback, 0.22f);
            quad(buffer, entry, x, y, z, x + 1, y + 1, z + 1, color);
            drawn++;
        }
    }

    private void renderOutlines(MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider consumers,
            Vec3d cameraPos) {
        if (!visible || timeline.eventCount() == 0) {
            return;
        }
        List<BlockEvent> events = timeline.visibleAtPlayhead();
        if (events.isEmpty()) {
            return;
        }
        VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
        boolean rollback = timeline.mode() == ReplayTimeline.Mode.ROLLBACK;
        int drawn = 0;
        for (int i = events.size() - 1; i >= 0 && drawn < MAX_BOXES; i--) {
            BlockEvent e = events.get(i);
            double x = e.x - cameraPos.getX();
            double y = e.y - cameraPos.getY();
            double z = e.z - cameraPos.getZ();
            int color = ghostColor(e, rollback, 1.0f);
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(), x, y, z, color, 0.02f);
            drawn++;
        }
    }

    /** Place = green, break = red, rollback preview = amber/white. */
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
    // Used by the timelapse exporter and rollback preview screen to resolve
    // historical states against the client's registry.
    // ------------------------------------------------------------------

    /**
     * Resolve a state string against the client registry. Unknown ids fall back
     * to the closest known block (namespace-tolerant), then to air.
     */
    public static BlockState parseState(String s) {
        if (s == null || s.isEmpty()) {
            return Blocks.AIR.getDefaultState();
        }
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
        return "events=" + eventCount() + "/" + expectedTotal() + " dim=" + dim
                + " mode=" + timeline.mode().name().toLowerCase(Locale.ROOT);
    }

    /** Snapshot of visible events (for exporter / UI). */
    public List<BlockEvent> visibleSnapshot() {
        return new ArrayList<>(timeline.visibleAtPlayhead());
    }
}
