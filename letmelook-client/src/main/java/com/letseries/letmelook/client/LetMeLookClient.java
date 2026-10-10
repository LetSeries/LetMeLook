package com.letseries.letmelook.client;

import com.letseries.letmelook.client.camera.CameraPath;
import com.letseries.letmelook.client.export.TimelapseExporter;
import com.letseries.letmelook.client.net.NetChannels;
import com.letseries.letmelook.client.net.TimelineReceiver;
import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.client.selection.SelectionState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** W2 client: networking + timeline + ghost renderer + camera + exporter. */
public final class LetMeLookClient implements ClientModInitializer {
    public static final String MOD_ID = "letmelook-client";

    private SelectionState selection;
    private GhostLayer ghostLayer;
    private CameraPath cameraPath;
    private TimelapseExporter exporter;

    @Override
    public void onInitializeClient() {
        this.selection = new SelectionState();
        this.ghostLayer = new GhostLayer();
        this.cameraPath = new CameraPath();
        this.exporter = new TimelapseExporter();

        // S2C timeline ingest (legacy receiver kept for W1 servers)
        TimelineReceiver.register(ghostLayer);
        NetChannels.register(ghostLayer);
        GhostLayer.register(ghostLayer);
        LetMeLookCommands.register(selection, ghostLayer, cameraPath, exporter);

        CameraPath.registerTick(cameraPath);
        TimelapseExporter.registerTick(exporter);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (ghostLayer != null) {
                ghostLayer.timeline().tick(1.0 / 20.0);
            }
        });
    }
}
