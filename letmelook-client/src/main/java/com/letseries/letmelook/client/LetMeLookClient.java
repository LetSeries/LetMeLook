package com.letseries.letmelook.client;

import com.letseries.letmelook.client.camera.CameraPath;
import com.letseries.letmelook.client.export.TimelapseExporter;
import com.letseries.letmelook.client.net.NetChannels;
import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.client.render.SelectionRenderer;
import com.letseries.letmelook.client.selection.SelectionState;
import com.letseries.letmelook.client.state.LookupStatusTracker;
import com.letseries.letmelook.client.ui.HudOverlay;
import com.letseries.letmelook.common.LetMeLookProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: networking + timeline + ghost renderer + selection
 * renderer + camera + exporter + overlay + keybinds.
 */
public final class LetMeLookClient implements ClientModInitializer {
    public static final String MOD_ID = "letmelook-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        SelectionState selection = new SelectionState();
        GhostLayer ghostLayer = new GhostLayer();
        CameraPath cameraPath = new CameraPath();
        TimelapseExporter exporter = new TimelapseExporter();
        HudOverlay hud = new HudOverlay();
        LookupStatusTracker statuses = new LookupStatusTracker(ghostLayer.timeline());

        // One registration of letmelook:lookup in each direction. W2 also
        // registered the S2C type via TimelineReceiver, which made Fabric throw
        // "already registered" and killed the client on startup.
        NetChannels.register(ghostLayer, statuses::accept);
        GhostLayer.register(ghostLayer);
        SelectionRenderer.register(selection);
        hud.register(ghostLayer, statuses, selection);
        LetMeLookKeys.register();
        LetMeLookKeys.registerTick(ghostLayer.timeline(), hud);
        LetMeLookCommands.register(selection, ghostLayer, cameraPath, exporter, statuses, hud);

        CameraPath.registerTick(cameraPath);
        TimelapseExporter.registerTick(exporter);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ghostLayer.timeline().tick(1.0 / 20.0));

        LOGGER.info("LetMeLook client initialised (protocol {})", LetMeLookProtocol.PROTOCOL_VERSION);
    }
}
