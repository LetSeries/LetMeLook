package com.letseries.letmelook.common;

/**
 * Wire protocol constants shared by the Purpur server plugin
 * and the Fabric client mod.
 *
 * <p>Transport: Plugin Messaging / Custom Payload channel
 * {@code letmelook:lookup}. Payloads are Gson JSON, optionally
 * gzip-compressed and split into pages (see {@link TimelineChunk}).
 */
public final class LetMeLookProtocol {
    public static final String CHANNEL = "letmelook:lookup";

    /** Max records per TimelineChunk page (server-side limit). */
    public static final int PAGE_LIMIT = 10_000;

    /** Hard cap per request to protect the server (W1 default 50k). */
    public static final int REQUEST_CAP = 50_000;

    private LetMeLookProtocol() {}
}
