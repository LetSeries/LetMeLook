package com.letseries.letmelook.client;

import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.client.selection.SelectionState;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

/**
 * Client commands (W1):
 * <ul>
 *   <li>/lm pos1 / /lm pos2 - capture current block pos</li>
 *   <li>/lm status - show selection + buffered event count</li>
 *   <li>/lm lookup t:&lt;seconds&gt; u:&lt;user&gt; - send LookupRequest (W2 full impl)</li>
 * </ul>
 */
final class LetMeLookCommands {

    private LetMeLookCommands() {}

    static void register(SelectionState selection, GhostLayer ghostLayer) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var lm = ClientCommandManager.literal("lm");

            lm.then(ClientCommandManager.literal("pos1").executes(ctx -> {
                var p = ctx.getSource().getPlayer().getBlockPos();
                selection.setPos1(p.getX(), p.getY(), p.getZ());
                ctx.getSource().sendFeedback(Text.literal("LetMeLook pos1 = " + p.toShortString()));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("pos2").executes(ctx -> {
                var p = ctx.getSource().getPlayer().getBlockPos();
                selection.setPos2(p.getX(), p.getY(), p.getZ());
                ctx.getSource().sendFeedback(Text.literal("LetMeLook pos2 = " + p.toShortString()));
                return 1;
            }));

            lm.then(ClientCommandManager.literal("status").executes(ctx -> {
                String sel = selection.hasSelection()
                        ? java.util.Arrays.toString(selection.min()) + " -> "
                                + java.util.Arrays.toString(selection.max())
                        : "none";
                ctx.getSource().sendFeedback(Text.literal(
                        "LetMeLook selection=" + sel + " events=" + ghostLayer.eventCount()
                                + "/" + ghostLayer.expectedTotal()));
                return 1;
            }));

            // W2: build LookupRequest from these args and send via ServerPlayNetworking custom payload.
            lm.then(ClientCommandManager.literal("lookup")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1))
                            .then(ClientCommandManager.argument("user", StringArgumentType.string())
                                    .executes(ctx -> {
                                        ctx.getSource().sendFeedback(Text.literal(
                                                "LetMeLook lookup ships in W2 (network send). Selection cached."));
                                        return 1;
                                    }))));

            dispatcher.register(lm);
        });
    }
}
