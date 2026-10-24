package com.letseries.letmelook.client;

import com.letseries.letmelook.client.timeline.ReplayTimeline;
import com.letseries.letmelook.client.ui.HudOverlay;
import com.letseries.letmelook.client.ui.TimeSliderScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybinds: open the timeline slider and toggle the overlay.
 *
 * <p>Both are rebindable under Controls; W2 was command-only.
 */
public final class LetMeLookKeys {
    /** Default key for the timeline screen. */
    private static final int TIMELINE_DEFAULT = GLFW.GLFW_KEY_L;
    /** Default key for the overlay toggle. */
    private static final int HUD_DEFAULT = GLFW.GLFW_KEY_H;

    private static KeyBinding timelineKey;
    private static KeyBinding hudKey;

    private LetMeLookKeys() {}

    public static void register() {
        timelineKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.letmelook.timeline", InputUtil.Type.KEYSYM, TIMELINE_DEFAULT,
                KeyBinding.Category.MISC));
        hudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.letmelook.hud", InputUtil.Type.KEYSYM, HUD_DEFAULT,
                KeyBinding.Category.MISC));
    }

    public static void registerTick(ReplayTimeline timeline, HudOverlay hud) {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (timelineKey == null || hudKey == null) {
                return;
            }
            while (timelineKey.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) {
                    mc.setScreen(new TimeSliderScreen(timeline));
                }
            }
            while (hudKey.wasPressed()) {
                boolean on = hud.toggle();
                if (mc.player != null) {
                    mc.player.sendMessage(
                            Text.literal("LetMeLook overlay " + (on ? "on" : "off")), true);
                }
            }
        });
    }
}
