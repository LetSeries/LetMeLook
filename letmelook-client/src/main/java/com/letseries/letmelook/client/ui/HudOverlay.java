package com.letseries.letmelook.client.ui;

import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.client.selection.SelectionState;
import com.letseries.letmelook.client.state.LookupStatusTracker;
import com.letseries.letmelook.client.timeline.ReplayTimeline;
import com.letseries.letmelook.common.LookupStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Compact top-left overlay: mode, playhead, ingest progress and the last server
 * status. W2 had no HUD at all — the only way to know whether a lookup had
 * arrived was to re-open {@code /lm status}.
 *
 * <p>Toggled with {@code /lm hud} or the {@code key.letmelook.hud} keybind.
 * Respects {@code hudHidden} so it never leaks into a timelapse export.
 */
public final class HudOverlay {
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int PANEL_BG = 0x8C000000;
    private static final int BAR_BG = 0xFF303030;
    private static final int BAR_FG = 0xFF4FC3F7;
    private static final int BAR_W = 96;
    private static final int BAR_H = 4;

    private volatile boolean enabled = true;

    public boolean enabled() {
        return enabled;
    }

    /** @return the new enabled state. */
    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public void register(GhostLayer layer, LookupStatusTracker statuses, SelectionState selection) {
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> render(ctx, layer, statuses, selection));
    }

    private void render(DrawContext ctx, GhostLayer layer, LookupStatusTracker statuses,
            SelectionState selection) {
        if (!enabled) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.options == null || mc.options.hudHidden) {
            return;
        }
        TextRenderer textRenderer = mc.textRenderer;
        if (textRenderer == null) {
            return;
        }
        ReplayTimeline timeline = layer.timeline();

        List<Text> lines = new ArrayList<>(5);
        lines.add(Text.literal("LetMeLook ").formatted(Formatting.AQUA)
                .append(modeText(timeline)));

        if (timeline.eventCount() == 0) {
            lines.add(Text.literal("no timeline — /lm lookup <secs>").formatted(Formatting.GRAY));
        } else {
            double pct = timeline.playheadFraction() * 100.0;
            lines.add(Text.literal(fmt(timeline.playhead()) + " / " + fmt(timeline.maxTime()))
                    .formatted(Formatting.WHITE)
                    .append(Text.literal("  " + (int) Math.round(pct) + "%").formatted(Formatting.GRAY)));
            lines.add(eventsText(timeline));
        }
        lines.add(Text.literal("sel " + selection.describe()).formatted(Formatting.GRAY));
        lines.add(statusText(statuses));

        int width = 0;
        for (Text line : lines) {
            width = Math.max(width, textRenderer.getWidth(line));
        }
        width = Math.max(width, BAR_W);
        int height = lines.size() * LINE_HEIGHT + BAR_H + 3 * PADDING;
        int x = PADDING;
        int y = PADDING;
        ctx.fill(x - PADDING, y - PADDING, x + width + PADDING, y + height, PANEL_BG);

        int ty = y;
        for (Text line : lines) {
            ctx.drawTextWithShadow(textRenderer, line, x, ty, 0xFFFFFF);
            ty += LINE_HEIGHT;
        }
        // ingest progress bar
        int barY = ty + PADDING;
        ctx.fill(x, barY, x + BAR_W, barY + BAR_H, BAR_BG);
        double frac = timeline.expectedTotal() <= 0 ? (timeline.eventCount() > 0 ? 1.0 : 0.0)
                : (double) timeline.eventCount() / (double) timeline.expectedTotal();
        int filled = (int) Math.round(BAR_W * Math.max(0.0, Math.min(1.0, frac)));
        if (filled > 0) {
            ctx.fill(x, barY, x + filled, barY + BAR_H, timeline.truncated() ? 0xFFFFB300 : BAR_FG);
        }
    }

    private static Text modeText(ReplayTimeline timeline) {
        Formatting color = switch (timeline.mode()) {
            case REPLAY -> Formatting.GREEN;
            case ROLLBACK -> Formatting.GOLD;
            case DIFF -> Formatting.LIGHT_PURPLE;
        };
        String label = timeline.mode().name();
        if (timeline.mode() == ReplayTimeline.Mode.DIFF) {
            label += "(" + (long) timeline.diffWindowSeconds() + "s)";
        }
        String play = timeline.isPlaying() ? " > " : " || ";
        String speed = timeline.isPlaying() ? " " + fmtSpeed(timeline.speed()) : "";
        return Text.literal(label + play + speed).formatted(color);
    }

    private static Text eventsText(ReplayTimeline timeline) {
        Text base = Text.literal("events " + timeline.eventCount() + "/" + timeline.expectedTotal()
                + "  pages " + timeline.pages()).formatted(Formatting.GRAY);
        if (timeline.truncated()) {
            return base.copy().append(Text.literal("  TRUNCATED").formatted(Formatting.GOLD));
        }
        if (!timeline.isComplete() && timeline.expectedTotal() > 0) {
            return base.copy().append(Text.literal("  …").formatted(Formatting.YELLOW));
        }
        return base;
    }

    private static Text statusText(LookupStatusTracker statuses) {
        LookupStatus last = statuses.last();
        if (last == null) {
            return Text.literal("status: —").formatted(Formatting.DARK_GRAY);
        }
        Formatting color = switch (last.state) {
            case ACCEPTED -> Formatting.GRAY;
            case DONE -> last.truncated ? Formatting.GOLD : Formatting.GREEN;
            case THROTTLED -> Formatting.YELLOW;
            case DENIED, ERROR -> Formatting.RED;
        };
        String detail = switch (last.state) {
            case ACCEPTED -> "scanning…";
            case DONE -> last.total + " events / " + last.pages + " pages / " + last.tookMs + "ms";
            default -> last.reason.isEmpty() ? last.state.name().toLowerCase() : last.reason;
        };
        return Text.literal("status " + last.state.name().toLowerCase() + ": " + detail).formatted(color);
    }

    private static String fmtSpeed(double s) {
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private static String fmt(long ms) {
        return ms <= 0 ? "--:--:--" : TIME_FMT.format(Instant.ofEpochMilli(ms));
    }
}
