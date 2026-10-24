package com.letseries.letmelook.client;

import com.letseries.letmelook.client.camera.CameraPath;
import com.letseries.letmelook.client.export.TimelapseExporter;
import com.letseries.letmelook.client.net.NetChannels;
import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.client.selection.SelectionState;
import com.letseries.letmelook.client.state.LookupStatusTracker;
import com.letseries.letmelook.client.timeline.ReplayTimeline;
import com.letseries.letmelook.client.ui.HudOverlay;
import com.letseries.letmelook.client.ui.TimeSliderScreen;
import com.letseries.letmelook.common.Codec;
import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client commands:
 * <ul>
 *   <li>/lm pos1|pos2 — capture selection corners (dimension-aware)</li>
 *   <li>/lm lookup &lt;seconds&gt; [user] — send LookupRequest to the server</li>
 *   <li>/lm status — selection + timeline + last server status</li>
 *   <li>/lm time — open the time slider screen</li>
 *   <li>/lm play|pause|speed — timelapse playback controls</li>
 *   <li>/lm mode replay|rollback|diff — ghost render mode</li>
 *   <li>/lm window &lt;seconds&gt; — DIFF-mode lookback window</li>
 *   <li>/lm hud — toggle the on-screen overlay</li>
 *   <li>/lm hide|show|clear — ghost visibility + buffer</li>
 *   <li>/lm cam add|clear|play|stop — camera path waypoints</li>
 *   <li>/lm export &lt;fps&gt; &lt;seconds&gt; — screenshot burst + ffmpeg script</li>
 * </ul>
 */
final class LetMeLookCommands {

    private static final AtomicInteger REQ_SEQ = new AtomicInteger(1);

    private LetMeLookCommands() {}

    static void register(SelectionState selection, GhostLayer ghostLayer, CameraPath cameraPath,
            TimelapseExporter exporter, LookupStatusTracker statuses, HudOverlay hud) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var lm = ClientCommandManager.literal("lm");

            lm.then(ClientCommandManager.literal("pos1").executes(ctx -> {
                var p = ctx.getSource().getPlayer().getBlockPos();
                boolean reset = selection.setPos1(p.getX(), p.getY(), p.getZ(),
                        currentDim(ctx.getSource()));
                ctx.getSource().sendFeedback(Text.literal("LetMeLook pos1 = " + fmtPos(p)
                        + " @" + SelectionState.shortDim(selection.dimension())
                        + (reset ? " (cleared pos2: different dimension)" : "")));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("pos2").executes(ctx -> {
                var p = ctx.getSource().getPlayer().getBlockPos();
                boolean reset = selection.setPos2(p.getX(), p.getY(), p.getZ(),
                        currentDim(ctx.getSource()));
                ctx.getSource().sendFeedback(Text.literal("LetMeLook pos2 = " + fmtPos(p)
                        + " @" + SelectionState.shortDim(selection.dimension())
                        + (reset ? " (cleared pos1: different dimension)" : "")));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("status").executes(ctx -> {
                var src = ctx.getSource();
                boolean hasSel = selection.hasSelection();
                src.sendFeedback(Text.literal("LetMeLook sel=" + selection.describe())
                        .formatted(hasSel ? Formatting.WHITE : Formatting.GRAY));
                src.sendFeedback(Text.literal("LetMeLook timeline " + ghostLayer.summary())
                        .formatted(Formatting.GRAY));
                src.sendFeedback(Text.literal("LetMeLook server " + statuses.summary())
                        .formatted(Formatting.GRAY));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("lookup")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 60 * 60 * 24 * 365))
                            .executes(ctx -> sendLookup(ctx.getSource(), selection, ghostLayer,
                                    IntegerArgumentType.getInteger(ctx, "seconds"), null))
                            .then(ClientCommandManager.argument("user", StringArgumentType.string())
                                    .executes(ctx -> sendLookup(ctx.getSource(), selection, ghostLayer,
                                            IntegerArgumentType.getInteger(ctx, "seconds"),
                                            StringArgumentType.getString(ctx, "user"))))));

            lm.then(ClientCommandManager.literal("time").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.execute(() -> mc.setScreen(new TimeSliderScreen(ghostLayer.timeline())));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("play").executes(ctx -> {
                ghostLayer.timeline().setPlaying(true);
                ctx.getSource().sendFeedback(Text.literal("LetMeLook playing"));
                return 1;
            }));
            lm.then(ClientCommandManager.literal("pause").executes(ctx -> {
                ghostLayer.timeline().setPlaying(false);
                ctx.getSource().sendFeedback(Text.literal("LetMeLook paused"));
                return 1;
            }));
            lm.then(ClientCommandManager.literal("speed")
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> {
                                double s = IntegerArgumentType.getInteger(ctx, "x");
                                ghostLayer.timeline().setSpeed(s);
                                ctx.getSource().sendFeedback(Text.literal("LetMeLook speed=" + s + "x"));
                                return 1;
                            })));

            lm.then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.literal("replay").executes(ctx -> {
                        ghostLayer.timeline().setMode(ReplayTimeline.Mode.REPLAY);
                        ctx.getSource().sendFeedback(Text.literal("LetMeLook mode=replay"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("rollback").executes(ctx -> {
                        ghostLayer.timeline().setMode(ReplayTimeline.Mode.ROLLBACK);
                        ctx.getSource().sendFeedback(Text.literal(
                                "LetMeLook mode=rollback (shows blocks a rollback to the playhead would change)"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("diff").executes(ctx -> {
                        ghostLayer.timeline().setMode(ReplayTimeline.Mode.DIFF);
                        ctx.getSource().sendFeedback(Text.literal("LetMeLook mode=diff window="
                                + (long) ghostLayer.timeline().diffWindowSeconds() + "s"));
                        return 1;
                    })));

            lm.then(ClientCommandManager.literal("window")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 3600))
                            .executes(ctx -> {
                                int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                ghostLayer.timeline().setDiffWindowSeconds(seconds);
                                ctx.getSource().sendFeedback(Text.literal(
                                        "LetMeLook diff window=" + seconds + "s"));
                                return 1;
                            })));

            lm.then(ClientCommandManager.literal("hud").executes(ctx -> {
                boolean on = hud.toggle();
                ctx.getSource().sendFeedback(Text.literal("LetMeLook overlay " + (on ? "on" : "off")));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("hide").executes(ctx -> {
                ghostLayer.setVisible(false);
                ctx.getSource().sendFeedback(Text.literal("LetMeLook ghosts hidden"));
                return 1;
            }));
            lm.then(ClientCommandManager.literal("show").executes(ctx -> {
                ghostLayer.setVisible(true);
                ctx.getSource().sendFeedback(Text.literal("LetMeLook ghosts shown"));
                return 1;
            }));
            lm.then(ClientCommandManager.literal("clear").executes(ctx -> {
                ghostLayer.clear();
                ctx.getSource().sendFeedback(Text.literal("LetMeLook timeline cleared"));
                return 1;
            }));

            var cam = ClientCommandManager.literal("cam");
            cam.then(ClientCommandManager.literal("add").executes(ctx -> {
                var p = ctx.getSource().getPlayer();
                var rot = ctx.getSource().getRotation();
                cameraPath.add(p.getX(), p.getY() + p.getEyeHeight(p.getPose()), p.getZ(),
                        rot.x, rot.y);
                ctx.getSource().sendFeedback(
                        Text.literal("LetMeLook cam waypoint #" + cameraPath.size()));
                return 1;
            }));
            cam.then(ClientCommandManager.literal("clear").executes(ctx -> {
                cameraPath.clear();
                ctx.getSource().sendFeedback(Text.literal("LetMeLook cam cleared"));
                return 1;
            }));
            cam.then(ClientCommandManager.literal("play").executes(ctx -> {
                if (cameraPath.size() < 2) {
                    ctx.getSource().sendError(Text.literal("LetMeLook cam needs >= 2 waypoints (/lm cam add)"));
                    return 0;
                }
                cameraPath.play(ghostLayer.timeline().playheadFraction());
                ctx.getSource().sendFeedback(Text.literal("LetMeLook cam playing"));
                return 1;
            }));
            cam.then(ClientCommandManager.literal("stop").executes(ctx -> {
                cameraPath.stop();
                ctx.getSource().sendFeedback(Text.literal("LetMeLook cam stopped"));
                return 1;
            }));
            lm.then(cam);

            lm.then(ClientCommandManager.literal("export")
                    .then(ClientCommandManager.argument("fps", IntegerArgumentType.integer(1, 60))
                            .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 600))
                                    .executes(ctx -> {
                                        int fps = IntegerArgumentType.getInteger(ctx, "fps");
                                        int secs = IntegerArgumentType.getInteger(ctx, "seconds");
                                        try {
                                            var out = exporter.begin(fps, secs,
                                                    ghostLayer.timeline(), cameraPath);
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "LetMeLook export -> " + out + " (hud hidden, capturing "
                                                            + (fps * secs) + " frames)"));
                                        } catch (Exception e) {
                                            ctx.getSource().sendError(
                                                    Text.literal("LetMeLook export failed: " + e.getMessage()));
                                            return 0;
                                        }
                                        return 1;
                                    }))));

            dispatcher.register(lm);
        });
    }

    private static String currentDim(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src) {
        return src.getWorld().getRegistryKey().getValue().toString();
    }

    private static String fmtPos(net.minecraft.util.math.BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static int sendLookup(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            SelectionState selection, GhostLayer ghostLayer, int seconds, String user) {
        if (!selection.hasSelection()) {
            src.sendError(Text.literal("LetMeLook: no selection (use /lm pos1, /lm pos2 first)"));
            return 0;
        }
        if (!ClientPlayNetworking.canSend(NetChannels.LookupPayload.ID.id())) {
            src.sendError(Text.literal("LetMeLook: server has no letmelook:lookup channel (is the plugin installed?)"));
            return 0;
        }
        LookupRequest req = new LookupRequest();
        req.world = currentDim(src);
        req.min = selection.min();
        req.max = selection.max();
        long now = System.currentTimeMillis();
        req.timeStart = now / 1000L - seconds;
        req.timeEnd = now / 1000L;
        req.users = new ArrayList<>();
        if (user != null && !user.equals("*") && !user.isEmpty()) {
            req.users.add(user);
        }
        req.actions = List.of("break", "place");
        req.page = 0;
        req.limit = LetMeLookProtocol.PAGE_LIMIT;
        req.requestId = REQ_SEQ.getAndIncrement();
        byte[] payload;
        try {
            payload = Codec.encodeAdaptive(req);
        } catch (Exception e) {
            src.sendError(Text.literal("LetMeLook: encode failed: " + e.getMessage()));
            return 0;
        }
        try {
            ClientPlayNetworking.send(new NetChannels.LookupPayload(payload));
        } catch (Exception e) {
            src.sendError(Text.literal("LetMeLook: send failed: " + e.getMessage()));
            return 0;
        }
        ghostLayer.timeline().setRequestId(req.requestId);
        src.sendFeedback(Text.literal("LetMeLook lookup req=" + req.requestId + " (" + seconds + "s"
                + (user != null ? " user=" + user : "") + ") "
                + selection.describe()));
        return 1;
    }
}
