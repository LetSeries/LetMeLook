package com.letseries.letmelook.client;

import com.letseries.letmelook.client.net.TimelineReceiver;
import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.client.selection.SelectionState;
import net.fabricmc.api.ClientModInitializer;

/** W1 scaffold: wire networking + selection store + ghost layer. */
public final class LetMeLookClient implements ClientModInitializer {
    public static final String MOD_ID = "letmelook-client";

    private SelectionState selection;
    private GhostLayer ghostLayer;

    @Override
    public void onInitializeClient() {
        this.selection = new SelectionState();
        this.ghostLayer = new GhostLayer();
        TimelineReceiver.register(ghostLayer);
        LetMeLookCommands.register(selection, ghostLayer);
    }
}
