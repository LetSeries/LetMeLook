package com.letseries.letmelook.common;

/**
 * Wire protocol constants shared by the Purpur server plugin
 * and the Fabric client mod.
 *
 * <p>Transport: Plugin Messaging / Custom Payload channel
 * {@code letmelook:lookup}. Payloads are Gson JSON, optionally
 * gzip-compressed and split into pages (see {@link TimelineChunk}).
 *
 * <p>Every payload carries a {@code kind} discriminator
 * ({@link #KIND_TIMELINE} or {@link #KIND_STATUS}) so one channel can carry
 * both bulk timeline pages and small lifecycle/status acknowledgements.
 * Payloads produced by W1/W2 servers have no {@code kind} field; their
 * {@link TimelineChunk} default makes them decode as timeline pages, so the
 * client stays compatible with older plugins.
 */
public final class LetMeLookProtocol {
    public static final String CHANNEL = "letmelook:lookup";

    /** Bumped whenever the payload contract changes (W3 added status messages). */
    public static final int PROTOCOL_VERSION = 3;

    /** Max records per TimelineChunk page (server-side limit). */
    public static final int PAGE_LIMIT = 10_000;

    /** Hard cap per request to protect the server (W1 default 50k). */
    public static final int REQUEST_CAP = 50_000;

    /** {@link TimelineChunk} discriminator value. */
    public static final String KIND_TIMELINE = "timeline";

    /** {@link LookupStatus} discriminator value. */
    public static final String KIND_STATUS = "status";

    /** Longest reason string the server will put on the wire. */
    public static final int MAX_REASON_LENGTH = 256;

    /** Default DIFF-mode lookback window (seconds). */
    public static final int DEFAULT_DIFF_WINDOW_SECONDS = 60;

    private LetMeLookProtocol() {}

    /** Trim + bound a human-readable reason so a hostile request can't bloat replies. */
    public static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String s = reason.strip();
        return s.length() <= MAX_REASON_LENGTH ? s : s.substring(0, MAX_REASON_LENGTH);
    }
}
