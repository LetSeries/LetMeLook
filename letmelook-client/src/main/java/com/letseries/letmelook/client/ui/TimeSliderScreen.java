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

/**
 * W2 time slider: scrub the replay playhead, play/pause, speed, mode.
 * Open with {@code /lm time}.
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
                }).dimensions(cx - 150, height - 52, 70, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Speed: " + fmtSpeed()),
                b -> {
                    double s = nextSpeed(timeline.speed());
                    timeline.setSpeed(s);
                    b.setMessage(Text.literal("Speed: " + fmtSpeed()));
                }).dimensions(cx - 72, height - 52, 80, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Mode: " + timeline.mode().name()),
                b -> {
                    timeline.setMode(nextMode(timeline.mode()));
                    b.setMessage(Text.literal("Mode: " + timeline.mode().name()));
                }).dimensions(cx + 16, height - 52, 90, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx + 114, height - 52, 60, 20).build());
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
        ctx.drawCenteredTextWithShadow(textRenderer, "LetMeLook Timeline", width / 2, 20, 0xFFFFFF);
        long lo = timeline.minTime();
        long hi = timeline.maxTime();
        long cur = timeline.playhead();
        String range = timeline.eventCount() == 0 ? "no data — run /lm lookup first"
                : fmt(lo) + "  ->  " + fmt(cur) + "  /  " + fmt(hi)
                        + "   (" + timeline.eventCount() + "/" + timeline.expectedTotal() + " events)";
        ctx.drawCenteredTextWithShadow(textRenderer, range, width / 2, 36, 0xAAAAAA);
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
