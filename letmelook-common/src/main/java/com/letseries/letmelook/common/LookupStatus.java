package com.letseries.letmelook.common;

/**
 * Server -&gt; Client lifecycle acknowledgement for one {@link LookupRequest}.
 *
 * <p>Before W3 a denied or throttled request was only written to the server log,
 * so the client waited forever with no explanation. The server now always
 * answers a request: {@link State#ACCEPTED} right after validation, then one
 * {@link State#DONE} (or {@link State#ERROR}) when the scan finishes, or a
 * terminal {@link State#DENIED}/{@link State#THROTTLED} without scanning.
 *
 * <p>{@link #kind} carries {@link LetMeLookProtocol#KIND_STATUS} so the client
 * can tell a status object from a {@link TimelineChunk} page on the shared
 * channel.
 */
public final class LookupStatus {
    public enum State {
        /** Request validated; the bulk scan is queued/running. */
        ACCEPTED,
        /** Refused (permission, malformed request, volume cap). */
        DENIED,
        /** Refused by the rate limiter; retry after {@link #retryAfterMs}. */
        THROTTLED,
        /** The scan failed server-side. */
        ERROR,
        /** All pages have been sent; the payload above is complete. */
        DONE
    }

    public String kind = LetMeLookProtocol.KIND_STATUS;
    public int requestId;
    public State state = State.ERROR;
    /** Human-readable detail (already bounded by {@link LetMeLookProtocol#sanitizeReason}). */
    public String reason = "";
    /** Events actually sent (DONE only). */
    public int total;
    /** Pages sent (DONE only). */
    public int pages;
    /** True when {@link #total} is a lower bound because the server hit its cap. */
    public boolean truncated;
    /** Server-side wall time of the scan in milliseconds (DONE only). */
    public long tookMs;
    /** Hint for THROTTLED: milliseconds until the next request would be accepted. */
    public long retryAfterMs;
    /** Protocol version of the sending plugin. */
    public int protocol = LetMeLookProtocol.PROTOCOL_VERSION;

    public LookupStatus() {}

    public static LookupStatus of(int requestId, State state, String reason) {
        LookupStatus s = new LookupStatus();
        s.requestId = requestId;
        s.state = state;
        s.reason = LetMeLookProtocol.sanitizeReason(reason);
        return s;
    }

    public boolean isTerminal() {
        return state != State.ACCEPTED;
    }

    @Override
    public String toString() {
        return "LookupStatus#" + requestId + " " + state
                + (reason.isEmpty() ? "" : " (" + reason + ")")
                + (state == State.DONE ? " total=" + total + " pages=" + pages + " tookMs=" + tookMs : "");
    }
}
