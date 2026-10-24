package com.letseries.letmelook.bukkit;

import com.letseries.letmelook.common.LetMeLookProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /letmelook status|reload} — admin visibility into the bridge.
 *
 * <p>W2 had no command at all: an operator had no way to see whether the
 * channel was registered, CoreProtect was hooked, or which limits were active.
 */
final class LetMeLookCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.AQUA + "[LetMeLook] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of("status", "reload");

    private final LetMeLookPlugin plugin;

    LetMeLookCommand(LetMeLookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("letmelook.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You lack letmelook.admin.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "config.yml reloaded.");
                status(sender);
            }
            case "status" -> status(sender);
            default -> sender.sendMessage(PREFIX + "Usage: /" + label + " <status|reload>");
        }
        return true;
    }

    private void status(CommandSender sender) {
        var config = plugin.getConfig();
        RequestLimiter limiter = plugin.limiter();
        sender.sendMessage(PREFIX + "LetMeLook " + ChatColor.YELLOW + plugin.getPluginMeta().getVersion()
                + ChatColor.RESET + " (protocol " + LetMeLookProtocol.PROTOCOL_VERSION + ")");
        boolean hooked = plugin.coreProtectApi() != null && plugin.coreProtectApi().isEnabled();
        sender.sendMessage(PREFIX + "CoreProtect: "
                + (hooked ? ChatColor.GREEN + "hooked" : ChatColor.RED + "NOT hooked")
                + ChatColor.RESET + "  channel: " + LetMeLookProtocol.CHANNEL
                + "  uptime: " + (plugin.uptimeMs() / 1000L) + "s");
        if (limiter != null) {
            sender.sendMessage(PREFIX + "limits: max-volume=" + limiter.maxVolume()
                    + " cooldown=" + limiter.cooldownMs() + "ms"
                    + " per-minute=" + limiter.maxPerMinute()
                    + " page-bytes=" + config.getInt("limits.page-bytes", 64 * 1024)
                    + " max-events=" + config.getInt("limits.max-events", LetMeLookProtocol.REQUEST_CAP)
                    + " pages-per-tick=" + config.getInt("limits.pages-per-tick", 4)
                    + " tracked-players=" + limiter.trackedPlayers());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("letmelook.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        return List.of();
    }
}
