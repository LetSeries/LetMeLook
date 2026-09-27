package com.letseries.letmelook.client.render;

import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.List;

/**
 * W1 scaffold: in-memory timeline store.
 *
 * <p>W2 turns this into the real ghost renderer (translucent BlockState
 * layer via WorldRenderEvents, time slider, rebuild/rollback/diff modes).
 * For now it just accumulates pages so the networking path is verifiable.
 */
public final class GhostLayer {
    private final List<BlockEvent> events = new ArrayList<>();
    private int lastRequestId = -1;
    private int lastTotal = 0;

    public synchronized void ingest(TimelineChunk chunk) {
        if (chunk.requestId != lastRequestId) {
            events.clear();
            lastRequestId = chunk.requestId;
        }
        lastTotal = chunk.total;
        if (chunk.events != null) {
            events.addAll(chunk.events);
        }
        events.sort((a, b) -> Long.compare(a.t, b.t));
    }

    public synchronized int eventCount() {
        return events.size();
    }

    public synchronized int expectedTotal() {
        return lastTotal;
    }

    public synchronized boolean isComplete() {
        return lastTotal > 0 && events.size() >= lastTotal;
    }
}
