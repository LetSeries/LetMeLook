package com.letseries.letmelook.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -&gt; Client paged timeline payload.
 *
 * <p>{@link #kind} defaults to {@link LetMeLookProtocol#KIND_TIMELINE}, which is
 * what a W1/W2 server (no {@code kind} field on the wire) decodes to.
 */
public final class TimelineChunk {
    public String kind = LetMeLookProtocol.KIND_TIMELINE;
    public int requestId;
    public int page;
    /** Total number of pages for this request (0 when the server is an older build). */
    public int pages;
    public int total;
    /** True when the server stopped early at its event cap: {@link #total} is then a lower bound. */
    public boolean truncated;
    public List<BlockEvent> events = new ArrayList<>();

    public TimelineChunk() {}

    /** True when this chunk is the last page of the request. */
    public boolean isLastPage() {
        if (pages > 0) {
            return page >= pages - 1;
        }
        return false;
    }
}
