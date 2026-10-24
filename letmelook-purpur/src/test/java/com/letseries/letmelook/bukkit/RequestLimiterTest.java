package com.letseries.letmelook.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupRequest;
import com.letseries.letmelook.common.LookupStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Admission policy tests. {@link RequestLimiter} takes a UUID and an explicit
 * clock and uses no Bukkit types, so the whole policy runs here without a
 * server.
 */
class RequestLimiterTest {

    private static LookupRequest request(String world, int[] min, int[] max) {
        LookupRequest req = new LookupRequest();
        req.world = world;
        req.min = min;
        req.max = max;
        req.limit = LetMeLookProtocol.PAGE_LIMIT;
        return req;
    }

    private static LookupRequest unitRequest() {
        return request("minecraft:overworld", new int[] {0, 0, 0}, new int[] {1, 1, 1});
    }

    @Test
    @DisplayName("malformed regions are rejected")
    void rejectsMalformedRegions() {
        assertNotNull(RequestLimiter.validateShape(null, 1000));
        assertNotNull(RequestLimiter.validateShape(request("w", null, new int[] {1, 2, 3}), 1000));
        assertNotNull(RequestLimiter.validateShape(request("w", new int[] {1, 2, 3}, null), 1000));
        assertNotNull(RequestLimiter.validateShape(request("", new int[] {0, 0, 0}, new int[] {1, 1, 1}), 1000));
        assertNotNull(RequestLimiter.validateShape(request(null, new int[] {0, 0, 0}, new int[] {1, 1, 1}), 1000));
        assertNotNull(RequestLimiter.validateShape(request("w", new int[] {0, 0}, new int[] {1, 1, 1}), 1000));
        assertNotNull(RequestLimiter.validateShape(
                request("w", new int[] {5, 0, 0}, new int[] {4, 1, 1}), 1000));
    }

    @Test
    @DisplayName("reversed axes cannot multiply into a plausible volume")
    void reversedAxesAreRejected() {
        // dx = -9, dy = -9, dz = 1 would be +81 if the spans were multiplied naively.
        assertNotNull(RequestLimiter.validateShape(
                request("w", new int[] {10, 10, 0}, new int[] {0, 0, 1}), 1000));
    }

    @Test
    @DisplayName("volume cap is enforced per region")
    void enforcesVolumeCap() {
        assertNull(RequestLimiter.validateShape(
                request("w", new int[] {0, 0, 0}, new int[] {9, 9, 9}), 1000), "1000 blocks fits");
        assertNotNull(RequestLimiter.validateShape(
                request("w", new int[] {0, 0, 0}, new int[] {10, 9, 9}), 1000), "1100 blocks does not");
        assertNotNull(RequestLimiter.validateShape(unitRequest(), 0), "maxVolume <= 0 disables lookups");
    }

    @Test
    @DisplayName("page limit field is validated")
    void enforcesPageLimit() {
        LookupRequest req = unitRequest();
        req.limit = 0;
        assertNotNull(RequestLimiter.validateShape(req, 1000));
        req.limit = LetMeLookProtocol.PAGE_LIMIT + 1;
        assertNotNull(RequestLimiter.validateShape(req, 1000));
        req.limit = LetMeLookProtocol.PAGE_LIMIT;
        assertNull(RequestLimiter.validateShape(req, 1000));
    }

    @Test
    @DisplayName("cooldown throttles, then clears")
    void cooldownThrottlesThenClears() {
        RequestLimiter limiter = new RequestLimiter(4096, 3000L, 100, 600_000L);
        UUID id = UUID.randomUUID();
        LookupRequest req = unitRequest();
        long t0 = 1_000_000L;

        assertTrue(limiter.check(id, req, t0).allowed());

        RequestLimiter.Verdict second = limiter.check(id, req, t0 + 500L);
        assertFalse(second.allowed());
        assertEquals(LookupStatus.State.THROTTLED, second.state());
        assertEquals(2500L, second.retryAfterMs());
        assertEquals(LookupStatus.State.THROTTLED, second.state());

        assertTrue(limiter.check(id, req, t0 + 3000L).allowed(), "cooldown is inclusive at the boundary");
    }

    @Test
    @DisplayName("a rejected shape does not consume the cooldown")
    void malformedRequestIsDeniedBeforeCooldown() {
        RequestLimiter limiter = new RequestLimiter(4096, 3000L, 100, 600_000L);
        UUID id = UUID.randomUUID();

        RequestLimiter.Verdict denied = limiter.check(id, request("w", null, null), 1_000_000L);
        assertFalse(denied.allowed());
        assertEquals(LookupStatus.State.DENIED, denied.state());

        assertTrue(limiter.check(id, unitRequest(), 1_000_000L).allowed());
    }

    @Test
    @DisplayName("per-minute cap rejects the extra request and rolls over")
    void perMinuteCap() {
        RequestLimiter limiter = new RequestLimiter(4096, 0L, 2, 600_000L);
        UUID id = UUID.randomUUID();
        LookupRequest req = unitRequest();
        long t = 5_000_000L;

        assertTrue(limiter.check(id, req, t).allowed());
        assertTrue(limiter.check(id, req, t + 10L).allowed());

        RequestLimiter.Verdict third = limiter.check(id, req, t + 20L);
        assertFalse(third.allowed());
        assertEquals(LookupStatus.State.THROTTLED, third.state());
        assertEquals(59_980L, third.retryAfterMs());

        assertTrue(limiter.check(id, req, t + 60_001L).allowed(), "window rolls over after a minute");
    }

    @Test
    @DisplayName("cooldown=0 and cap=0 disable those guards")
    void disabledGuards() {
        RequestLimiter limiter = new RequestLimiter(4096, 0L, 0, 600_000L);
        UUID id = UUID.randomUUID();
        long t = 9_000_000L;
        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.check(id, unitRequest(), t + i).allowed());
        }
    }

    @Test
    @DisplayName("idle per-player state is evicted")
    void stateIsEvictedAfterTtl() {
        RequestLimiter limiter = new RequestLimiter(4096, 0L, 0, 60_000L);
        LookupRequest req = unitRequest();
        long t = 1_000_000L;
        for (int i = 0; i < 40; i++) {
            limiter.check(UUID.randomUUID(), req, t);
        }
        assertEquals(40, limiter.trackedPlayers());

        limiter.sweep(t);
        assertEquals(40, limiter.trackedPlayers(), "nothing is idle yet");

        limiter.sweep(t + 60_001L);
        assertEquals(0, limiter.trackedPlayers(), "state older than the TTL must be dropped");
    }

    @Test
    @DisplayName("accessors expose the configured limits")
    void accessorsExposeLimits() {
        RequestLimiter limiter = new RequestLimiter(1234, 500L, 7, 60_000L);
        assertEquals(1234, limiter.maxVolume());
        assertEquals(500L, limiter.cooldownMs());
        assertEquals(7, limiter.maxPerMinute());
    }

    @Test
    @DisplayName("volume helper multiplies inclusive spans")
    void volumeHelper() {
        assertEquals(8L, RequestLimiter.volume(new int[] {0, 0, 0}, new int[] {1, 1, 1}));
        assertEquals(1L, RequestLimiter.volume(new int[] {5, 5, 5}, new int[] {5, 5, 5}));
    }
}
