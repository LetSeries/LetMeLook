package com.letseries.letmelook.client.render;

import com.letseries.letmelook.client.selection.SelectionState;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Draws the {@code /lm pos1}/{@code pos2} cuboid as a wireframe so the player can
 * see what a lookup will cover.
 *
 * <p>W2's {@link SelectionState} was documented as "rendered as highlight box"
 * but nothing ever drew it, so {@code /lm pos1} gave no visual feedback at all.
 *
 * <p>Vertices are camera-relative (same convention as {@link GhostLayer}): the
 * render matrices are not translated, the camera offset is baked into the
 * coordinates. The region hull is one {@link VoxelShapes#cuboid} outline, so cost
 * is constant regardless of region size.
 */
public final class SelectionRenderer {
    private static final int HULL = ColorHelper.fromFloats(0.85f, 0.30f, 0.85f, 1.0f);
    private static final int CORNER_1 = ColorHelper.fromFloats(1.0f, 0.35f, 0.55f, 1.0f);
    private static final int CORNER_2 = ColorHelper.fromFloats(1.0f, 0.95f, 0.45f, 1.0f);
    /** Half-extent of the per-corner marker cube, in blocks. */
    private static final double MARKER = 0.4;

    private SelectionRenderer() {}

    public static void register(SelectionState selection) {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ctx -> render(ctx, selection));
    }

    private static void render(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext ctx,
            SelectionState selection) {
        if (!selection.hasSelection()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return;
        }
        String currentDim = mc.world.getRegistryKey().getValue().toString();
        if (!currentDim.equals(selection.dimension())) {
            return; // selection lives in another dimension
        }
        int[] min = selection.min();
        int[] max = selection.max();
        Vec3d cam = ctx.worldState().cameraRenderState.pos;
        MatrixStack matrices = ctx.matrices();
        VertexConsumer lines = ctx.consumers().getBuffer(RenderLayers.lines());

        double ox = min[0] - cam.getX();
        double oy = min[1] - cam.getY();
        double oz = min[2] - cam.getZ();

        // One outline for the whole region: constant cost for any region size.
        VertexRendering.drawOutline(matrices, lines,
                VoxelShapes.cuboid(0.0, 0.0, 0.0,
                        max[0] - min[0] + 1.0, max[1] - min[1] + 1.0, max[2] - min[2] + 1.0),
                ox, oy, oz, HULL, 0.06f);

        int[] p1 = selection.pos1();
        int[] p2 = selection.pos2();
        if (p1 != null) {
            drawMarker(matrices, lines, cam, p1, CORNER_1);
        }
        if (p2 != null) {
            drawMarker(matrices, lines, cam, p2, CORNER_2);
        }
    }

    private static void drawMarker(MatrixStack matrices, VertexConsumer lines, Vec3d cam, int[] pos, int color) {
        double x = pos[0] - cam.getX();
        double y = pos[1] - cam.getY();
        double z = pos[2] - cam.getZ();
        VertexRendering.drawOutline(matrices, lines,
                VoxelShapes.cuboid(0.5 - MARKER, 0.5 - MARKER, 0.5 - MARKER,
                        0.5 + MARKER, 0.5 + MARKER, 0.5 + MARKER),
                x, y, z, color, 0.08f);
    }
}
