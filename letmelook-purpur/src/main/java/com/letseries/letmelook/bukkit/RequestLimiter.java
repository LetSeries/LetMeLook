package com.letseries.letmelook.bukkit;

import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import com.letseries.letmelook.common.LookupStatus;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player admission control: shape/volume validation, cooldown and a
 * requests-per-minute cap.
 *
 * <p>W2 returned a nullable {@code String} and only logged it, so a rejected
 * client learned nothing. W3 returns a {@link Verdict} carrying the
 * {@link LookupStatus.State} and reason the plugin forwards to the player.
 *
 * <p>Deliberately free of Bukkit types: the caller supplies the player UUID and
 * the clock, so the whole policy is unit-testable without a running server, and
 * it keeps no reference to {@code Player} objects (no leak across logouts).
 *
 * <p>W2 also kept every player UUID forever; {@link #sweep(long)} now evicts
 * state idle for longer than the configured TTL.
 */
final class RequestLimiter {

    /** Outcome of an admission check. */
    record Verdict(boolean allowed, LookupStatus.State state, String reason, long retryAfterMs) {
        static final Verdict ALLOW = new Verdict(true, LookupStatus.State.ACCEPTED, "", 0L);

        static Verdict deny(String reason) {
            return new Verdict(false, LookupStatus.State.DENIED, reason, 0L);
        }

        static Verdict throttle(String reason, long retryAfterMs) {
            return new Verdict(false, LookupStatus.State.THROTTLED, reason, Math.max(0L, retryAfterMs));
        }
    }

    /** Mutable per-player limiter state; mutated under its own monitor. */
    static final class Entry {
        long lastMs;
        long windowStartMs;
        int windowCount;
    }

    private static final long MINUTE_MS = 60_000L;

    private final int maxVolume;
    private final long cooldownMs;
    private final int maxPerMinute;
    private final long idleTtlMs;
    private final Map<UUID, Entry> state = new ConcurrentHashMap<>();

    RequestLimiter(int maxVolume, long cooldownMs, int maxPerMinute, long idleTtlMs) {
        this.maxVolume = maxVolume;
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.maxPerMinute = maxPerMinute;
        this.idleTtlMs = Math.max(MINUTE_MS, idleTtlMs);
    }

    int maxVolume() {
        return maxVolume;
    }

    long cooldownMs() {
        return cooldownMs;
    }

    int maxPerMinute() {
        return maxPerMinute;
    }

    /** Number of players with live limiter state (for /letmelook status). */
    int trackedPlayers() {
        return state.size();
    }

    /** Admission check against the wall clock. */
    Verdict check(UUID playerId, LookupRequest req) {
        return check(playerId, req, System.currentTimeMillis());
    }

    /** Clock-injected policy core. */
    Verdict check(UUID playerId, LookupRequest req, long nowMs) {
        String rejection = validateShape(req, maxVolume);
        if (rejection != null) {
            return Verdict.deny(rejection);
        }
        sweep(nowMs);
        Entry entry = state.computeIfAbsent(playerId, key -> new Entry());
        synchronized (entry) {
            if (cooldownMs > 0 && entry.lastMs != 0 && nowMs - entry.lastMs < cooldownMs) {
                long wait = cooldownMs - (nowMs - entry.lastMs);
                return Verdict.throttle("cooldown " + wait + "ms remaining", wait);
            }
            if (maxPerMinute > 0) {
                if (entry.windowStartMs == 0 || nowMs - entry.windowStartMs >= MINUTE_MS) {
                    entry.windowStartMs = nowMs;
                    entry.windowCount = 0;
                }
                if (entry.windowCount >= maxPerMinute) {
                    long wait = MINUTE_MS - (nowMs - entry.windowStartMs);
                    return Verdict.throttle(
                            "rate limit " + maxPerMinute + "/min reached", Math.max(0L, wait));
                }
            }
            entry.lastMs = nowMs;
            entry.windowCount++;
            return Verdict.ALLOW;
        }
    }

    /**
     * Pure region validation. Returns {@code null} when the request is
     * acceptable, otherwise a human-readable reason.
     *
     * <p>Each axis is checked for {@code max >= min} before multiplying, so
     * reversed coordinates can never produce a positive-but-meaningless volume
     * (e.g. {@code dx=-9, dy=-9, dz=1} would multiply out to {@code +81}), and
     * the product is capped early so absurd coordinates cannot overflow
     * {@code long}.
     */
    static String validateShape(LookupRequest req, int maxVolume) {
        if (req == null || req.min == null || req.max == null
                || req.min.length < 3 || req.max.length < 3
                || req.world == null || req.world.isEmpty()) {
            return "malformed request (missing region/world)";
        }
        if (maxVolume <= 0) {
            return "lookups disabled (limits.max-volume <= 0)";
        }
        for (int axis = 0; axis < 3; axis++) {
            if (req.max[axis] < req.min[axis]) {
                return "malformed region (max < min on axis " + axis + ")";
            }
        }
        long volume = 1L;
        for (int axis = 0; axis < 3; axis++) {
            volume *= (long) req.max[axis] - (long) req.min[axis] + 1L;
            if (volume > maxVolume) {
                return "region volume " + volume + "+ exceeds max " + maxVolume;
            }
        }
        if (req.limit <= 0 || req.limit > LetMeLookProtocol.PAGE_LIMIT) {
            return "page limit out of range";
        }
        return null;
    }

    /** Volume of a validated region (call only after {@link #validateShape} passes). */
    static long volume(int[] min, int[] max) {
        return ((long) max[0] - min[0] + 1L)
                * ((long) max[1] - min[1] + 1L)
                * ((long) max[2] - min[2] + 1L);
    }

    /** Drop state idle for longer than the TTL. Cheap; runs on each check. */
    void sweep(long nowMs) {
        if (state.size() < 32) {
            return;
        }
        Iterator<Map.Entry<UUID, Entry>> it = state.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            synchronized (e) {
                if (e.lastMs != 0 && nowMs - e.lastMs > idleTtlMs) {
                    it.remove();
                }
            }
        }
    }
}
