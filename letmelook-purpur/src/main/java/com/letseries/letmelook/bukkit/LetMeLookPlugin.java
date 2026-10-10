package com.letseries.letmelook.bukkit;

import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.Codec;
import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.CoreProtectAPI.ParseResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * LetMeLook Purpur/Paper bridge (W2: cuboid bulk scan).
 *
 * <p>Receives {@link LookupRequest} on {@code letmelook:lookup}, runs ONE
 * CoreProtect {@code performLookup} cuboid scan (sphere envelope + Java-side
 * cuboid clip, instead of the W1 per-block {@code blockLookup} loop) and
 * streams back gzip-framed {@link TimelineChunk} pages sized by byte budget.
 *
 * <p>Heavy lifting stays async; only the final send hops to the main thread
 * (Bukkit requires {@code sendPluginMessage} on the main thread).
 */
public final class LetMeLookPlugin extends JavaPlugin implements PluginMessageListener {

    /** Cap a single encoded page after gzip (Bukkit/Vanilla custom payload safety). */
    private static final int MAX_PAGE_BYTES = 256 * 1024;

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
        if (!(plugin instanceof net.coreprotect.CoreProtect)) {
            return false;
        }
        this.coApi = ((net.coreprotect.CoreProtect) plugin).getAPI();
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
            req = Codec.decodeAdaptive(message, LookupRequest.class);
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
     * W2 bulk path: a single {@code performLookup} over the sphere envelope of
     * the requested cuboid, then Java-side clip to the cuboid + user/action/time
     * filters. {@code ParseResult.getTimestamp()} is epoch millis.
     */
    private List<BlockEvent> queryCoreProtect(LookupRequest req) {
        List<BlockEvent> out = new ArrayList<>();
        World world = Bukkit.getWorld(req.world);
        if (world == null || coApi == null) {
            return out;
        }
        int[] min = req.min;
        int[] max = req.max;
        if (min == null || max == null || min.length < 3 || max.length < 3) {
            return out;
        }

        long nowSec = System.currentTimeMillis() / 1000L;
        int lookback = (int) Math.max(1, nowSec - req.timeStart);
        long endSec = req.timeEnd > 0 ? req.timeEnd : nowSec;

        double cx = (min[0] + max[0]) / 2.0;
        double cy = (min[1] + max[1]) / 2.0;
        double cz = (min[2] + max[2]) / 2.0;
        double dx = (double) max[0] - min[0] + 1;
        double dy = (double) max[1] - min[1] + 1;
        double dz = (double) max[2] - min[2] + 1;
        int radius = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 2.0);
        Location center = new Location(world, cx, cy, cz);

        List<Integer> actions = parseActions(req.actions);
        List<String> users = req.users != null ? req.users : List.of();

        List<String[]> rows;
        try {
            rows = coApi.performLookup(
                    lookback, users, List.of(), List.of(), List.of(), actions, radius, center);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "performLookup failed req=" + req.requestId, e);
            return out;
        }
        if (rows == null) {
            return out;
        }
        for (String[] raw : rows) {
            if (out.size() >= LetMeLookProtocol.REQUEST_CAP) {
                break;
            }
            ParseResult row;
            try {
                row = coApi.parseResult(raw);
            } catch (Exception e) {
                continue;
            }
            if (row == null) {
                continue;
            }
            int x = row.getX();
            int y = row.getY();
            int z = row.getZ();
            if (x < min[0] || x > max[0] || y < min[1] || y > max[1] || z < min[2] || z > max[2]) {
                continue; // sphere envelope clip -> cuboid
            }
            if (!users.isEmpty() && !users.contains(row.getPlayer())) {
                continue;
            }
            if (!actions.isEmpty() && !actions.contains(row.getActionId())) {
                continue;
            }
            long tSec = row.getTimestamp() / 1000L;
            if (tSec < req.timeStart || tSec > endSec) {
                continue;
            }
            String state = stateString(row);
            int action = row.getActionId();
            // CoreProtect rows carry the post-change block type: break(0) removed
            // `state`, place(1) added `state`. Replace-overwrite keeps from=air as
            // an approximation (CO API exposes no pre-change state here).
            String from = action == 0 ? state : "minecraft:air";
            String to = action == 0 ? "minecraft:air" : state;
            out.add(new BlockEvent(row.getTimestamp(), x, y, z, from, to, row.getPlayer(), action));
        }
        out.sort((a, b) -> Long.compare(a.t, b.t));
        return out;
    }

    private static String stateString(ParseResult row) {
        try {
            var data = row.getBlockData();
            if (data != null) {
                return data.getAsString(false);
            }
        } catch (Exception ignored) {
            // fall through to material name
        }
        try {
            var type = row.getType();
            if (type != null) {
                return "minecraft:" + type.name().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "minecraft:air";
    }

    /** Accepts "break"/"place"/"0"/"1" (case-insensitive); default = break+place. */
    static List<Integer> parseActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of(0, 1);
        }
        List<Integer> out = new ArrayList<>();
        for (String a : actions) {
            if (a == null) {
                continue;
            }
            String s = a.trim().toLowerCase(Locale.ROOT);
            if (s.equals("break") || s.equals("0")) {
                if (!out.contains(0)) {
                    out.add(0);
                }
            } else if (s.equals("place") || s.equals("1")) {
                if (!out.contains(1)) {
                    out.add(1);
                }
            }
        }
        return out.isEmpty() ? List.of(0, 1) : out;
    }

    /**
     * Split the timeline into pages that fit the byte budget, gzip-encode each
     * page (adaptive framing: the client auto-detects gzip), and send on the
     * main thread. Page count, not event count, bounds the transfer.
     */
    private void sendPaged(Player player, LookupRequest req, List<BlockEvent> events) {
        int pageBytes = Math.max(4096, getConfig().getInt("limits.page-bytes", 64 * 1024));
        int total = events.size();
        List<byte[]> pages = new ArrayList<>();
        int i = 0;
        while (i < total) {
            int count = Math.min(LetMeLookProtocol.PAGE_LIMIT, total - i);
            byte[] payload = null;
            while (true) {
                TimelineChunk probe = new TimelineChunk();
                probe.requestId = req.requestId;
                probe.page = pages.size();
                probe.total = total;
                probe.events = new ArrayList<>(events.subList(i, i + count));
                byte[] raw;
                try {
                    raw = Codec.encode(probe);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Encode failed req=" + req.requestId, e);
                    return;
                }
                try {
                    payload = raw.length >= Codec.COMPRESSION_THRESHOLD ? Codec.compress(raw) : raw;
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Compress failed req=" + req.requestId, e);
                    return;
                }
                if (payload.length <= pageBytes || count <= 64) {
                    break;
                }
                count /= 2;
            }
            if (payload.length > MAX_PAGE_BYTES) {
                getLogger().warning("Dropping oversize page req=" + req.requestId
                        + " bytes=" + payload.length);
                i += count;
                continue;
            }
            pages.add(payload);
            i += count;
        }
        if (pages.isEmpty()) {
            // still send one (possibly empty) page so the client can finish the request
            TimelineChunk empty = new TimelineChunk();
            empty.requestId = req.requestId;
            empty.page = 0;
            empty.total = 0;
            try {
                pages.add(Codec.encode(empty));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Encode failed req=" + req.requestId, e);
                return;
            }
        }
        for (byte[] payload : pages) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    player.sendPluginMessage(this, LetMeLookProtocol.CHANNEL, payload);
                }
            });
        }
        Location c = new Location(Bukkit.getWorld(req.world), req.min[0], req.min[1], req.min[2]);
        getLogger().info("Audit req=" + req.requestId + " player=" + player.getName()
                + " events=" + total + " pages=" + pages.size() + " center=" + c);
    }

    /** Accessor for tests / future bulk paths. */
    CoreProtectAPI coreProtectApi() {
        return coApi;
    }
}
