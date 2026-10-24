package com.letseries.letmelook.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Status message defaults, reason bounds and terminality. */
class LookupStatusTest {

    @Test
    @DisplayName("defaults carry the status kind and current protocol version")
    void defaultsMatchProtocol() {
        LookupStatus status = new LookupStatus();
        assertEquals(LetMeLookProtocol.KIND_STATUS, status.kind);
        assertEquals(LetMeLookProtocol.PROTOCOL_VERSION, status.protocol);
        assertEquals(LookupStatus.State.ERROR, status.state);
        assertTrue(status.reason.isEmpty());
    }

    @Test
    @DisplayName("reason is trimmed and bounded")
    void reasonIsSanitized() {
        assertEquals("too big", LookupStatus.of(1, LookupStatus.State.DENIED, "   too big  ").reason);
        assertEquals("", LookupStatus.of(1, LookupStatus.State.DENIED, null).reason);
        assertEquals("", LookupStatus.of(1, LookupStatus.State.DENIED, "   ").reason);

        String huge = "x".repeat(10_000);
        assertEquals(LetMeLookProtocol.MAX_REASON_LENGTH,
                LookupStatus.of(1, LookupStatus.State.ERROR, huge).reason.length());
    }

    @Test
    @DisplayName("only ACCEPTED is non-terminal")
    void terminality() {
        assertFalse(LookupStatus.of(1, LookupStatus.State.ACCEPTED, "").isTerminal());
        assertTrue(LookupStatus.of(1, LookupStatus.State.DONE, "").isTerminal());
        assertTrue(LookupStatus.of(1, LookupStatus.State.DENIED, "").isTerminal());
        assertTrue(LookupStatus.of(1, LookupStatus.State.THROTTLED, "").isTerminal());
        assertTrue(LookupStatus.of(1, LookupStatus.State.ERROR, "").isTerminal());
    }

    @Test
    @DisplayName("factory carries the request id through")
    void factoryCarriesRequestId() {
        LookupStatus status = LookupStatus.of(77, LookupStatus.State.DENIED, "nope");
        assertEquals(77, status.requestId);
        assertEquals(LookupStatus.State.DENIED, status.state);
        assertEquals("nope", status.reason);
    }
}
