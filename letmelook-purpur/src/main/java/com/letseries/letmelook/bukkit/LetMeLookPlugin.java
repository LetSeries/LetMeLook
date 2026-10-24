package com.letseries.letmelook.bukkit;

import com.letseries.letmelook.common.ActionFilter;
import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.Codec;
import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import com.letseries.letmelook.common.LookupStatus;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.CoreProtectAPI.ParseResult;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * LetMeLook Purpur/Paper bridge (W3: status protocol + throttled delivery).
 *
 * <p>Receives {@link LookupRequest} on {@code letmelook:lookup}, runs ONE
 * CoreProtect {@code performLookup} cuboid scan (sphere envelope + Java-side
 * cuboid clip) and streams back gzip-framed {@link TimelineChunk} pages sized by
 * byte budget.
 *
 * <p>W3 changes:
 * <ul>
 *   <li>Every request is acknowledged with a {@link LookupStatus}: the client no
 *       longer hangs silently when a lookup is denied, throttled or fails.</li>
 *   <li>Pages are delivered a few per tick instead of all in one tick, so a
 *       large scan cannot dump tens of megabytes into a single server tick.</li>
 *   <li>{@code DONE} is sent only after the last page, so the client can trust
 *       ordering; the event cap is reported as {@code truncated}.</li>
 * </ul>
 *
 * <p>Heavy lifting stays async; only the final send hops to the main thread
 * (Bukkit requires {@code sendPluginMessage} on the main thread).
 */
public final class LetMeLookPlugin extends JavaPlugin implements PluginMessageListener {

    /** Cap a single encoded page after gzip (Bukkit/Vanilla custom payload safety). */
    private static final int MAX_PAGE_BYTES = 256 * 1024;

    private CoreProtectAPI coApi;
    private RequestLimiter limiter;
    private final long startedAtMs = System.currentTimeMillis();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.limiter = buildLimiter();

        if (!hookCoreProtect()) {
            getLogger().severe("CoreProtect not found - LetMeLook disabled. Install CoreProtect CE 23.2+.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, LetMeLookProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, LetMeLookProtocol.CHANNEL, this);

        var command = getCommand("letmelook");
        if (command != null) {
            LetMeLookCommand handler = new LetMeLookCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("command 'letmelook' missing from plugin.yml");
        }

        getLogger().info("LetMeLook enabled, channel=" + LetMeLookProtocol.CHANNEL
                + " protocol=" + LetMeLookProtocol.PROTOCOL_VERSION);
    }

    /** Re-read config.yml and rebuild the limiter. Used by {@code /letmelook reload}. */
    void reloadPluginConfig() {
        reloadConfig();
        this.limiter = buildLimiter();
    }

    private RequestLimiter buildLimiter() {
        var config = getConfig();
        return new RequestLimiter(
                config.getInt("limits.max-volume", 128 * 128 * 64),
                config.getLong("limits.cooldown-ms", 3000L),
                config.getInt("limits.requests-per-minute", 12),
                config.getLong("limits.state-ttl-ms", 15L * 60_000L));
    }

    RequestLimiter limiter() {
        return limiter;
    }

    CoreProtectAPI coreProtectApi() {
        return coApi;
    }

    long uptimeMs() {
        return System.currentTimeMillis() - startedAtMs;
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
        final LookupRequest req;
        try {
            req = Codec.decodeAdaptive(message, LookupRequest.class);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Bad LookupRequest from " + player.getName(), e);
            sendStatus(player, LookupStatus.of(-1, LookupStatus.State.ERROR, "malformed request payload"));
            return;
        }
        if (req == null) {
            sendStatus(player, LookupStatus.of(-1, LookupStatus.State.ERROR, "empty request payload"));
            return;
        }
        final int requestId = req.requestId;

        if (!player.hasPermission("letmelook.use")) {
            getLogger().warning("Denied lookup from " + player.getName() + " (missing letmelook.use)");
            sendStatus(player, LookupStatus.of(requestId, LookupStatus.State.DENIED,
                    "missing permission letmelook.use"));
            return;
        }

        RequestLimiter.Verdict verdict = limiter.check(player.getUniqueId(), req);
        if (!verdict.allowed()) {
            getLogger().info("Rejected lookup req=" + requestId + " from " + player.getName()
                    + ": " + verdict.reason());
            LookupStatus status = LookupStatus.of(requestId, verdict.state(), verdict.reason());
            status.retryAfterMs = verdict.retryAfterMs();
            sendStatus(player, status);
            return;
        }

        sendStatus(player, LookupStatus.of(requestId, LookupStatus.State.ACCEPTED, ""));
        getLogger().info("Lookup req=" + requestId + " from " + player.getName()
                + " world=" + req.world + " users=" + req.users);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> runLookup(player, req));
    }

    /** Outcome of one CoreProtect scan. */
    private record ScanResult(List<BlockEvent> events, boolean truncated) {}

    private void runLookup(Player player, LookupRequest req) {
        long startNs = System.nanoTime();
        try {
            ScanResult scan = queryCoreProtect(req);
            sendPaged(player, req, scan, (System.nanoTime() - startNs) / 1_000_000L);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Lookup failed req=" + req.requestId, e);
            sendStatus(player, LookupStatus.of(req.requestId, LookupStatus.State.ERROR,
                    "server scan failed: " + e.getClass().getSimpleName()));
        }
    }

    /**
     * W2 bulk path: a single {@code performLookup} over the sphere envelope of
     * the requested cuboid, then Java-side clip to the cuboid + user/action/time
     * filters. {@code ParseResult.getTimestamp()} is epoch millis.
     */
    private ScanResult queryCoreProtect(LookupRequest req) {
        List<BlockEvent> out = new ArrayList<>();
        World world = Bukkit.getWorld(req.world);
        if (world == null || coApi == null) {
            return new ScanResult(out, false);
        }
        int[] min = req.min;
        int[] max = req.max;
        if (min == null || max == null || min.length < 3 || max.length < 3) {
            return new ScanResult(out, false);
        }

        int maxEvents = Math.max(1, getConfig().getInt("limits.max-events", LetMeLookProtocol.REQUEST_CAP));
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
        org.bukkit.Location center = new org.bukkit.Location(world, cx, cy, cz);

        List<Integer> actions = ActionFilter.parse(req.actions);
        List<String> users = req.users != null ? req.users : List.of();
        // Case-insensitive mirror of the requested names: CoreProtect's own
        // matching may differ in case, and a mismatch here would silently drop
        // rows the scan returned.
        List<String> usersLower = new ArrayList<>(users.size());
        for (String u : users) {
            if (u != null && !u.isBlank()) {
                usersLower.add(u.toLowerCase(Locale.ROOT));
            }
        }

        List<String[]> rows;
        try {
            rows = coApi.performLookup(
                    lookback, users, List.of(), List.of(), List.of(), actions, radius, center);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "performLookup failed req=" + req.requestId, e);
            return new ScanResult(out, false);
        }
        if (rows == null) {
            return new ScanResult(out, false);
        }

        boolean truncated = false;
        for (String[] raw : rows) {
            if (out.size() >= maxEvents) {
                truncated = true;
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
            String rowPlayer = row.getPlayer();
            if (!usersLower.isEmpty()
                    && (rowPlayer == null || !usersLower.contains(rowPlayer.toLowerCase(Locale.ROOT)))) {
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
            out.add(new BlockEvent(row.getTimestamp(), x, y, z, from, to, rowPlayer, action));
        }
        out.sort((a, b) -> Long.compare(a.t, b.t));
        return new ScanResult(out, truncated);
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

    /**
     * Split the timeline into pages that fit the byte budget and gzip-encode
     * each page. The page list is built first so every page can carry the final
     * page count, then delivered a few per tick.
     */
    private void sendPaged(Player player, LookupRequest req, ScanResult scan, long scanMs) {
        List<BlockEvent> events = scan.events();
        int total = events.size();
        int pageBytes = Math.max(4096, getConfig().getInt("limits.page-bytes", 64 * 1024));
        int maxPages = Math.max(1, getConfig().getInt("limits.max-pages", 512));

        // Phase 1: choose page ranges that fit the byte budget. Probing starts at
        // the size that fit the previous page, so a large scan does not re-encode
        // a full PAGE_LIMIT chunk for every page.
        List<int[]> ranges = new ArrayList<>(); // {start, count}
        int i = 0;
        int probeHint = LetMeLookProtocol.PAGE_LIMIT;
        while (i < total && ranges.size() < maxPages) {
            int count = Math.min(probeHint, Math.min(LetMeLookProtocol.PAGE_LIMIT, total - i));
            while (true) {
                int encoded = encodedSize(req.requestId, ranges.size(), 0, total, events, i, count);
                if (encoded < 0) {
                    return; // encode failure already logged
                }
                if (encoded <= pageBytes || count <= 64) {
                    break;
                }
                count /= 2;
            }
            ranges.add(new int[] {i, count});
            probeHint = Math.max(64, count);
            i += count;
        }
        boolean truncated = scan.truncated() || i < total;

        // Phase 2: encode with the final page count (a few bytes larger; the byte
        // budget is a target, MAX_PAGE_BYTES is the hard ceiling).
        int pages = ranges.size();
        List<byte[]> payloads = new ArrayList<>(pages);
        for (int p = 0; p < pages; p++) {
            int[] range = ranges.get(p);
            byte[] payload = encodeChunk(req.requestId, p, pages, total, truncated, events,
                    range[0], range[1]);
            if (payload == null) {
                return;
            }
            if (payload.length > MAX_PAGE_BYTES) {
                getLogger().warning("Dropping oversize page req=" + req.requestId
                        + " bytes=" + payload.length);
                continue;
            }
            payloads.add(payload);
        }
        if (pages == 0) {
            // No events: still send one (possibly empty) page so W2-era clients,
            // which have no status handling, finish their request cleanly.
            byte[] empty = encodeChunk(req.requestId, 0, 1, 0, false, List.of(), 0, 0);
            if (empty != null) {
                payloads.add(empty);
                pages = 1;
            }
        }

        int deliveredPages = payloads.size();
        final boolean doneTruncated = truncated;
        int perTick = Math.max(1, getConfig().getInt("limits.pages-per-tick", 4));
        getLogger().info("Lookup req=" + req.requestId + " player=" + player.getName()
                + " events=" + total + " pages=" + pages + " truncated=" + truncated
                + " scanMs=" + scanMs);

        sendPagesThrottled(player, payloads, perTick, () -> {
            LookupStatus done = LookupStatus.of(req.requestId, LookupStatus.State.DONE, "");
            done.total = total;
            done.pages = deliveredPages;
            done.truncated = doneTruncated;
            done.tookMs = scanMs;
            sendStatus(player, done);
        });
    }

    /** Encode probe used while fitting a page to the byte budget. */
    private int encodedSize(int requestId, int page, int pages, int total,
            List<BlockEvent> events, int start, int count) {
        byte[] payload = encodeChunk(requestId, page, pages, total, false, events, start, count);
        return payload == null ? -1 : payload.length;
    }

    private byte[] encodeChunk(int requestId, int page, int pages, int total, boolean truncated,
            List<BlockEvent> events, int start, int count) {
        TimelineChunk chunk = new TimelineChunk();
        chunk.requestId = requestId;
        chunk.page = page;
        chunk.pages = pages;
        chunk.total = total;
        chunk.truncated = truncated;
        chunk.events = new ArrayList<>(events.subList(start, start + count));
        try {
            byte[] raw = Codec.encode(chunk);
            return raw.length >= Codec.COMPRESSION_THRESHOLD ? Codec.compress(raw) : raw;
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Encode failed req=" + requestId, e);
            return null;
        }
    }

    /**
     * Deliver {@code pages} {@code perTick} at a time on the main thread, then
     * run {@code onDone}. One packet per page; the delay keeps a large scan from
     * flooding a single tick.
     */
    private void sendPagesThrottled(Player player, List<byte[]> pages, int perTick, Runnable onDone) {
        if (pages.isEmpty()) {
            Bukkit.getScheduler().runTask(this, onDone);
            return;
        }
        AtomicInteger index = new AtomicInteger();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                for (int n = 0; n < perTick && index.get() < pages.size(); n++) {
                    byte[] payload = pages.get(index.getAndIncrement());
                    try {
                        player.sendPluginMessage(LetMeLookPlugin.this, LetMeLookProtocol.CHANNEL, payload);
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "sendPluginMessage failed for " + player.getName(), e);
                        cancel();
                        return;
                    }
                }
                if (index.get() >= pages.size()) {
                    cancel();
                    onDone.run();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** Send a status message, hopping to the main thread when needed. */
    private void sendStatus(Player player, LookupStatus status) {
        Runnable task = () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                player.sendPluginMessage(this, LetMeLookProtocol.CHANNEL, Codec.encode(status));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "status send failed for " + player.getName(), e);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }
}
