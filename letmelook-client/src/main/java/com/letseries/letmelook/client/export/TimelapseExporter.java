package com.letseries.letmelook.client.export;

import com.letseries.letmelook.client.camera.CameraPath;
import com.letseries.letmelook.client.timeline.ReplayTimeline;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

/**
 * W2 timelapse exporter: captures a screenshot burst while sweeping the
 * timeline playhead (optionally riding the {@link CameraPath}), then writes
 * an {@code ffmpeg} concat script next to the PNGs.
 *
 * <p>Flow: {@code /lm export <fps> <seconds>} hides the HUD, jumps the
 * playhead to the start, captures {@code fps*seconds} frames (one per client
 * tick, max 1/frame), restores the HUD, and writes
 * {@code make-timelapse.sh} so the user can render
 * {@code timelapse.mp4} with one command (ffmpeg must be installed).
 */
public final class TimelapseExporter {
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private boolean capturing = false;
    private File dir;
    private int fps;
    private int totalFrames;
    private int frameIndex;
    private ReplayTimeline timeline;
    private boolean prevHudHidden;
    private double prevSpeed;
    private boolean prevCamPlaying;

    public synchronized File begin(int fps, int seconds, ReplayTimeline timeline, CameraPath cameraPath)
            throws IOException {
        if (capturing) {
            throw new IOException("capture already in progress");
        }
        if (timeline.eventCount() == 0) {
            throw new IOException("no timeline data (run /lm lookup first)");
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            throw new IOException("not in game");
        }
        this.fps = Math.max(1, Math.min(60, fps));
        this.totalFrames = this.fps * Math.max(1, Math.min(600, seconds));
        this.frameIndex = 0;
        this.timeline = timeline;
        this.dir = new File(mc.runDirectory, "letmelook-timelapse-" + DIR_FMT.format(LocalDateTime.now()));
        if (!dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        this.prevHudHidden = mc.options.hudHidden;
        mc.options.hudHidden = true;
        this.prevSpeed = timeline.speed();
        timeline.setPlaying(false);
        timeline.setPlayheadFraction(0.0);
        this.prevCamPlaying = cameraPath.isPlaying();
        if (cameraPath.size() >= 2) {
            cameraPath.setDurationSec((double) totalFrames / 20.0);
            cameraPath.playFromStart();
        }
        this.capturing = true;
        return dir;
    }

    public synchronized boolean isCapturing() {
        return capturing;
    }

    public synchronized int progress() {
        return totalFrames == 0 ? 0 : (frameIndex * 100) / totalFrames;
    }

    /** Called from END_CLIENT_TICK: advance playhead + grab one frame. */
    public void tick(MinecraftClient mc) {
        File frameDir;
        ReplayTimeline tl;
        synchronized (this) {
            if (!capturing) {
                return;
            }
            frameDir = dir;
            tl = timeline;
        }
        if (mc.world == null || mc.player == null) {
            finish(mc);
            return;
        }
        // sweep playhead uniformly across the capture
        tl.setPlayheadFraction((double) frameIndex / (double) Math.max(1, totalFrames - 1));
        Framebuffer fb = mc.getFramebuffer();
        File out = new File(frameDir, String.format("frame-%05d.png", frameIndex));
        try {
            ScreenshotRecorder.takeScreenshot(fb, img -> writeFrame(img, out));
        } catch (Exception e) {
            // keep going; a dropped frame is better than an aborted export
        }
        synchronized (this) {
            frameIndex++;
            if (frameIndex >= totalFrames) {
                finish(mc);
            }
        }
    }

    private void writeFrame(NativeImage img, File out) {
        try (img) {
            img.writeTo(out);
        } catch (Exception ignored) {
            // dropped frame
        }
    }

    private synchronized void finish(MinecraftClient mc) {
        capturing = false;
        mc.options.hudHidden = prevHudHidden;
        if (timeline != null) {
            timeline.setSpeed(prevSpeed);
            timeline.setPlayheadFraction(1.0);
        }
        writeFfmpegScript(dir, fps);
    }

    private static void writeFfmpegScript(File dir, int fps) {
        String sh = "#!/bin/sh\n"
                + "# LetMeLook timelapse: renders frame-*.png into timelapse.mp4\n"
                + "# Requires ffmpeg: https://ffmpeg.org/download.html\n"
                + "set -e\n"
                + "cd \"$(dirname \"$0\")\"\n"
                + "ffmpeg -y -framerate " + fps + " -i frame-%05d.png"
                + " -c:v libx264 -pix_fmt yuv420p -crf 18 timelapse.mp4\n"
                + "echo done: timelapse.mp4\n";
        try {
            File script = new File(dir, "make-timelapse.sh");
            Files.write(script.toPath(), sh.getBytes(StandardCharsets.UTF_8));
            script.setExecutable(true);
        } catch (Exception ignored) {
            // PNGs are still usable without the script
        }
    }

    public static void registerTick(TimelapseExporter exporter) {
        ClientTickEvents.END_CLIENT_TICK.register(exporter::tick);
    }
}
