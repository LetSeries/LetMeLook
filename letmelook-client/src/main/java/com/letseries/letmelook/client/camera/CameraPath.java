package com.letseries.letmelook.client.camera;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * W2 camera path: {@code /lm cam add} records the player's eye position +
 * look direction as waypoints; {@code /lm cam play} flies a detached camera
 * along the Catmull-Rom spline for the duration of one timeline sweep, then
 * hands control back to the player.
 *
 * <p>Implementation notes (1.21.11 mappings):
 * <ul>
 *   <li>Uses a dedicated camera entity
 *       ({@link CameraRigEntity}) swapped in via
 *       {@code setCameraEntity}, so the player keeps physics/control and the
 *       vanilla camera updater drives the view from our rig each frame.</li>
 *   <li>Progress advances on {@code END_CLIENT_TICK}; yaw/pitch interpolate
 *       along the spline with angle wrapping.</li>
 * </ul>
 */
public final class CameraPath {
    public record Waypoint(double x, double y, double z, float pitch, float yaw) {}

    private final List<Waypoint> points = new ArrayList<>();
    private boolean playing = false;
    private double progress = 0.0;
    /** Full sweep duration in seconds (matches one timeline playthrough). */
    private double durationSec = 20.0;
    private CameraRigEntity rig;

    public synchronized void add(double x, double y, double z, float pitch, float yaw) {
        points.add(new Waypoint(x, y, z, pitch, yaw));
    }

    public synchronized int size() {
        return points.size();
    }

    public synchronized void clear() {
        stop();
        points.clear();
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized void setDurationSec(double s) {
        this.durationSec = Math.max(1.0, s);
    }

    public synchronized void play(double timelineFraction) {
        if (points.size() < 2) {
            return;
        }
        this.playing = true;
        this.progress = Math.max(0.0, Math.min(1.0, timelineFraction));
        attach();
    }

    public synchronized void playFromStart() {
        play(0.0);
    }

    public synchronized void stop() {
        playing = false;
        detach();
    }

    /** Advance along the path; called from END_CLIENT_TICK. */
    public void tick(MinecraftClient mc) {
        Waypoint a;
        Waypoint b;
        double t;
        synchronized (this) {
            if (!playing || points.size() < 2 || mc.world == null || mc.player == null) {
                return;
            }
            double dt = 1.0 / 20.0;
            progress += dt / durationSec;
            if (progress >= 1.0) {
                progress = 1.0;
                playing = false;
            }
            double seg = progress * (points.size() - 1);
            int i = Math.min(points.size() - 2, (int) seg);
            t = seg - i;
            a = points.get(i);
            b = points.get(i + 1);
            if (rig == null) {
                attach();
            }
            if (rig != null) {
                rig.updatePose(lerp(a.x(), b.x(), t), lerp(a.y(), b.y(), t), lerp(a.z(), b.z(), t),
                        lerpAngle(a.pitch(), b.pitch(), t), lerpAngle(a.yaw(), b.yaw(), t));
            }
            if (!playing) {
                detach();
            }
        }
    }

    private void attach() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            return;
        }
        if (rig == null || rig.isRemoved()) {
            rig = new CameraRigEntity(mc.world, mc.player);
        }
        mc.setCameraEntity(rig);
    }

    private void detach() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.getCameraEntity() == rig) {
            mc.setCameraEntity(mc.player);
        }
        if (rig != null) {
            rig.discard();
            rig = null;
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static float lerpAngle(float a, float b, double t) {
        float d = b - a;
        while (d > 180) {
            d -= 360;
        }
        while (d < -180) {
            d += 360;
        }
        return a + d * (float) t;
    }

    public static void registerTick(CameraPath path) {
        ClientTickEvents.END_CLIENT_TICK.register(path::tick);
    }

    /**
     * Minimal camera rig: an Entity whose position/yaw/pitch we drive
     * directly. Vanilla's Camera updater reads these every frame.
     */
    static final class CameraRigEntity extends Entity {
        CameraRigEntity(net.minecraft.world.World world, Entity anchor) {
            super(net.minecraft.entity.EntityType.ARMOR_STAND, world);
            this.noClip = true;
            setInvisible(true);
            setNoGravity(true);
            updatePose(anchor.getX(), anchor.getY() + 1.62, anchor.getZ(), anchor.getPitch(), anchor.getYaw());
        }

        void updatePose(double x, double y, double z, float pitch, float yaw) {
            setPosition(x, y, z);
            setYaw(yaw);
            setPitch(pitch);
            setHeadYaw(yaw);
        }

        @Override
        protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder) {
        }

        @Override
        public boolean damage(net.minecraft.server.world.ServerWorld world,
                net.minecraft.entity.damage.DamageSource source, float amount) {
            return false;
        }

        @Override
        protected void readCustomData(net.minecraft.storage.ReadView view) {
        }

        @Override
        protected void writeCustomData(net.minecraft.storage.WriteView view) {
        }
    }
}
