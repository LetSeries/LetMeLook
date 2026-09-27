package com.letseries.letmelook.bukkit;

import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.Codec;
import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.CoreProtectAPI.ParseResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * LetMeLook Purpur/Paper bridge (W1 scaffold).
 *
 * <p>Receives {@link LookupRequest} on {@code letmelook:lookup}, runs a
 * CoreProtect lookup (CE 24.x typed API) and streams back paged
 * {@link TimelineChunk}s. Heavy lifting stays async; only the final
 * send hops back to the main thread.
 */
public final class LetMeLookPlugin extends JavaPlugin implements PluginMessageListener {

    private CoreProtectAPI coApi;
    private RequestLimiter limiter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.limiter = new RequestLimiter(getConfig());

        if (!hookCoreProtect()) {
            getLogger().severe("CoreProtect not found - LetMeLook disabled. Install CoreProtect CE 23.2+.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, LetMeLookProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, LetMeLookProtocol.CHANNEL, this);
        getLogger().info("LetMeLook enabled, channel=" + LetMeLookProtocol.CHANNEL);
    }

    private boolean hookCoreProtect() {
        var plugin = getServer().getPluginManager().getPlugin("CoreProtect");
        if (!(plugin instanceof CoreProtect)) {
            return false;
        }
        this.coApi = ((CoreProtect) plugin).getAPI();
        return this.coApi != null && this.coApi.isEnabled();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(LetMeLookProtocol.CHANNEL)) {
            return;
        }
        if (!player.hasPermission("letmelook.use")) {
            getLogger().warning("Denied lookup from " + player.getName() + " (missing letmelook.use)");
            return;
        }
        final LookupRequest req;
        try {
            req = Codec.decode(message, LookupRequest.class);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Bad LookupRequest from " + player.getName(), e);
            return;
        }
        String verdict = limiter.check(player, req);
        if (verdict != null) {
            getLogger().warning("Throttled lookup from " + player.getName() + ": " + verdict);
            return;
        }
        getLogger().info("Lookup req=" + req.requestId + " from " + player.getName()
                + " world=" + req.world + " users=" + req.users);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> runLookup(player, req));
    }

    private void runLookup(Player player, LookupRequest req) {
        try {
            List<BlockEvent> events = queryCoreProtect(req);
            sendPaged(player, req, events);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Lookup failed req=" + req.requestId, e);
        }
    }

    /**
     * CoreProtect CE 24.x path: {@code blockLookup(Block, seconds)} returns
     * {@code List<String[]>} rows parsed via {@code parseResult()}. W1 covers
     * small regions; the cuboid bulk scan ({@code performLookup}) lands in W2.
     */
    private List<BlockEvent> queryCoreProtect(LookupRequest req) {
        List<BlockEvent> out = new ArrayList<>();
        World world = Bukkit.getWorld(req.world);
        if (world == null || coApi == null) {
            return out;
        }
        int[] min = req.min;
        int[] max = req.max;
        long volume = (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
        if (volume > 512) {
            // W1 guard: small regions only; bulk scan arrives in W2.
            getLogger().warning("Region too large for W1 scaffold (" + volume + " blocks), truncating.");
            max = new int[] {min[0], min[1], min[2]};
        }
        int seconds = (int) Math.max(1, req.timeEnd - req.timeStart);
        for (int x = min[0]; x <= max[0] && out.size() < LetMeLookProtocol.REQUEST_CAP; x++) {
            for (int y = min[1]; y <= max[1] && out.size() < LetMeLookProtocol.REQUEST_CAP; y++) {
                for (int z = min[2]; z <= max[2] && out.size() < LetMeLookProtocol.REQUEST_CAP; z++) {
                    var block = world.getBlockAt(x, y, z);
                    List<String[]> rows = coApi.blockLookup(block, seconds);
                    if (rows == null) {
                        continue;
                    }
                    for (String[] raw : rows) {
                        ParseResult row;
                        try {
                            row = coApi.parseResult(raw);
                        } catch (Exception e) {
                            continue;
                        }
                        if (row == null) {
                            continue;
                        }
                        if (!req.users.isEmpty() && !req.users.contains(row.getPlayer())) {
                            continue;
                        }
                        String state = row.getBlockData() != null
                                ? row.getBlockData().getAsString(false)
                                : "minecraft:air";
                        // action: 0 = break, 1 = place (LookupActions)
                        out.add(new BlockEvent(
                                row.getTimestamp(), x, y, z,
                                state, state, row.getPlayer(), row.getActionId()));
                        if (out.size() >= LetMeLookProtocol.REQUEST_CAP) {
                            break;
                        }
                    }
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.t, b.t));
        return out;
    }

    private void sendPaged(Player player, LookupRequest req, List<BlockEvent> events) {
        int limit = Math.max(1, Math.min(req.limit, LetMeLookProtocol.PAGE_LIMIT));
        int total = events.size();
        int pages = Math.max(1, (total + limit - 1) / limit);
        for (int page = 0; page < pages; page++) {
            int from = page * limit;
            int to = Math.min(total, from + limit);
            TimelineChunk chunk = new TimelineChunk();
            chunk.requestId = req.requestId;
            chunk.page = page;
            chunk.total = total;
            chunk.events = new ArrayList<>(events.subList(from, to));
            byte[] payload = Codec.encode(chunk);
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    player.sendPluginMessage(this, LetMeLookProtocol.CHANNEL, payload);
                }
            });
        }
        // keep audit trail: who looked at which region
        Location c = new Location(Bukkit.getWorld(req.world), req.min[0], req.min[1], req.min[2]);
        getLogger().info("Audit req=" + req.requestId + " player=" + player.getName()
                + " events=" + total + " center=" + c);
    }

    /** Accessor for the W2 bulk path. */
    CoreProtectAPI coreProtectApi() {
        return coApi;
    }
}
