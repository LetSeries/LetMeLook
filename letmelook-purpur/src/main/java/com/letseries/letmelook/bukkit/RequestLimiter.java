package com.letseries.letmelook.bukkit;

import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/** Per-player cooldown + region volume guard. */
final class RequestLimiter {
    private final int maxVolume;
    private final long cooldownMs;
    private final Map<UUID, Long> lastSeen = new HashMap<>();

    RequestLimiter(FileConfiguration config) {
        this.maxVolume = config.getInt("limits.max-volume", 128 * 128 * 64);
        this.cooldownMs = config.getLong("limits.cooldown-ms", 3000);
    }

    /** Returns null when allowed, otherwise a human-readable reason. */
    synchronized String check(Player player, LookupRequest req) {
        long volume = (long) (req.max[0] - req.min[0] + 1)
                * (req.max[1] - req.min[1] + 1)
                * (req.max[2] - req.min[2] + 1);
        if (volume <= 0 || volume > maxVolume) {
            return "region volume " + volume + " exceeds max " + maxVolume;
        }
        long now = System.currentTimeMillis();
        Long last = lastSeen.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            return "cooldown " + (cooldownMs - (now - last)) + "ms remaining";
        }
        if (req.limit <= 0 || req.limit > LetMeLookProtocol.PAGE_LIMIT) {
            return "page limit out of range";
        }
        lastSeen.put(player.getUniqueId(), now);
        return null;
    }
}
