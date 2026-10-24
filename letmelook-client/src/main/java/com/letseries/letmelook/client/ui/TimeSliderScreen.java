package com.letseries.letmelook.client.ui;

import com.letseries.letmelook.client.timeline.ReplayTimeline;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Time slider: scrub the replay playhead, play/pause, speed, mode.
 * Open with {@code /lm time} or the timeline keybind.
 */
public final class TimeSliderScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ReplayTimeline timeline;
    private ScrubSlider slider;

    public TimeSliderScreen(ReplayTimeline timeline) {
        super(Text.literal("LetMeLook Timeline"));
        this.timeline = timeline;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        slider = new ScrubSlider(cx - 150, height - 80, 300, 20);
        addDrawableChild(slider);

        addDrawableChild(ButtonWidget.builder(
                Text.literal(timeline.isPlaying() ? "Pause" : "Play"),
                b -> {
                    timeline.setPlaying(!timeline.isPlaying());
                    b.setMessage(Text.literal(timeline.isPlaying() ? "Pause" : "Play"));
                }).dimensions(cx - 150, height - 52, 56, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Speed: " + fmtSpeed()),
                b -> {
                    timeline.setSpeed(nextSpeed(timeline.speed()));
                    b.setMessage(Text.literal("Speed: " + fmtSpeed()));
                }).dimensions(cx - 90, height - 52, 80, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Mode: " + modeLabel()),
                b -> {
                    timeline.setMode(nextMode(timeline.mode()));
                    b.setMessage(Text.literal("Mode: " + modeLabel()));
                }).dimensions(cx - 6, height - 52, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx + 98, height - 52, 52, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("|<"), b -> {
            timeline.setPlaying(false);
            timeline.setPlayheadFraction(0.0);
        }).dimensions(cx - 150, height - 28, 30, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal(">|"), b -> {
            timeline.setPlaying(false);
            timeline.setPlayheadFraction(1.0);
        }).dimensions(cx + 120, height - 28, 30, 18).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Window: " + (long) timeline.diffWindowSeconds() + "s"),
                b -> {
                    timeline.setDiffWindowSeconds(nextWindow(timeline.diffWindowSeconds()));
                    b.setMessage(Text.literal("Window: " + (long) timeline.diffWindowSeconds() + "s"));
                }).dimensions(cx - 60, height - 28, 100, 18).build());
    }

    private String modeLabel() {
        if (timeline.mode() == ReplayTimeline.Mode.DIFF) {
            return "DIFF " + (long) timeline.diffWindowSeconds() + "s";
        }
        return timeline.mode().name();
    }

    private String fmtSpeed() {
        double s = timeline.speed();
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private static double nextSpeed(double s) {
        double[] steps = {1, 2, 4, 8, 16, 32};
        for (double st : steps) {
            if (st > s + 0.01) {
                return st;
            }
        }
        return 1;
    }

    private static double nextWindow(double s) {
        double[] steps = {5, 15, 30, 60, 300, 900, 3600};
        for (double st : steps) {
            if (st > s + 0.01) {
                return st;
            }
        }
        return 5;
    }

    private static ReplayTimeline.Mode nextMode(ReplayTimeline.Mode m) {
        return switch (m) {
            case REPLAY -> ReplayTimeline.Mode.ROLLBACK;
            case ROLLBACK -> ReplayTimeline.Mode.DIFF;
            case DIFF -> ReplayTimeline.Mode.REPLAY;
        };
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, "LetMeLook Timeline", width / 2, 16, 0xFFFFFF);
        long lo = timeline.minTime();
        long hi = timeline.maxTime();
        long cur = timeline.playhead();
        String range = timeline.eventCount() == 0 ? "no data — run /lm lookup first"
                : fmt(lo) + "  ->  " + fmt(cur) + "  /  " + fmt(hi)
                        + "   (" + timeline.eventCount() + "/" + timeline.expectedTotal() + " events)";
        ctx.drawCenteredTextWithShadow(textRenderer, range, width / 2, 32, 0xAAAAAA);

        String meta = "mode=" + timeline.mode().name()
                + "  pages=" + timeline.pages()
                + "  visible=" + (timeline.eventCount() > 0)
                + (timeline.isComplete() ? "  complete" : "  loading…");
        ctx.drawCenteredTextWithShadow(textRenderer, meta, width / 2, 44, 0x888888);

        if (timeline.truncated()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("result TRUNCATED at the server event cap — narrow the region or time range")
                            .formatted(Formatting.GOLD),
                    width / 2, 58, 0xFFAA00);
        }
        if (slider != null) {
            slider.sync();
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) {
            return "--";
        }
        return FMT.format(Instant.ofEpochMilli(ms));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private final class ScrubSlider extends SliderWidget {
        ScrubSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.literal("Scrub"), timeline.playheadFraction());
            updateMessage();
        }

        void sync() {
            this.value = timeline.playheadFraction();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("t = " + fmt(timeline.playhead())));
        }

        @Override
        protected void applyValue() {
            timeline.setPlayheadFraction(value);
            timeline.setPlaying(false);
        }
    }
}
