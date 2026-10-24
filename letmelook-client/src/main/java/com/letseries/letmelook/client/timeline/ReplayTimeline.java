package com.letseries.letmelook.client.timeline;

import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory timeline: accumulates paged {@link TimelineChunk}s, keeps them
 * time-sorted, and exposes a playhead (epoch millis) used by the ghost
 * renderer and the time slider.
 *
 * <p>Playback modes (see {@code /lm mode}):
 * <ul>
 *   <li>{@link Mode#REPLAY} / {@link Mode#ROLLBACK} — every event at or before
 *       the playhead; the renderer folds these into a last-writer-wins world
 *       reconstruction.</li>
 *   <li>{@link Mode#DIFF} — only events inside the {@link #diffWindowMs()}
 *       window ending at the playhead, i.e. "what changed just now".</li>
 * </ul>
 *
 * <p>W3 fixes two W2 defects: a page from a superseded request could clear the
 * timeline that replaced it, and {@code isComplete()} never became true for an
 * empty result or a refused request.
 */
public final class ReplayTimeline {
    public enum Mode {
        REPLAY,
        ROLLBACK,
        DIFF
    }

    private final List<BlockEvent> events = new ArrayList<>();
    private int lastRequestId = -1;
    private int lastTotal = 0;
    private int pages = 0;
    private boolean truncated = false;
    private boolean settled = false;
    private int pendingRequestId = -1;

    /** Monotonic counter bumped whenever the event set changes (render caches). */
    private long revision = 0L;

    /**
     * Playhead in epoch millis. {@link Long#MAX_VALUE} means "pinned to the
     * newest event": pages arriving later are immediately visible instead of
     * being stuck in the future, and it stays pinned until the user scrubs,
     * plays or the exporter moves it.
     */
    private long playheadMs = Long.MAX_VALUE;
    private Mode mode = Mode.REPLAY;
    private boolean playing = false;
    private double speed = 4.0;
    private long diffWindowMs = LetMeLookProtocol.DEFAULT_DIFF_WINDOW_SECONDS * 1000L;

    public synchronized void ingest(TimelineChunk chunk) {
        if (chunk == null) {
            return;
        }
        if (pendingRequestId >= 0 && chunk.requestId != pendingRequestId) {
            // A page from a request the player already replaced: ignore it
            // instead of clearing the newer timeline.
            return;
        }
        if (chunk.requestId != lastRequestId) {
            events.clear();
            lastRequestId = chunk.requestId;
            lastTotal = 0;
            pages = 0;
            truncated = false;
            settled = false;
            playheadMs = Long.MAX_VALUE;
        }
        lastTotal = Math.max(lastTotal, chunk.total);
        if (chunk.pages > 0) {
            pages = chunk.pages;
        }
        if (chunk.truncated) {
            truncated = true;
        }
        if (chunk.events != null && !chunk.events.isEmpty()) {
            events.addAll(chunk.events);
            events.sort((a, b) -> Long.compare(a.t, b.t));
        }
        if (lastTotal > 0 && events.size() >= lastTotal) {
            settled = true;
        }
        revision++;
    }

    /**
     * Mark the request as fully answered by the server (a terminal
     * {@code LookupStatus} arrived). This is what makes {@code isComplete()}
     * meaningful for an empty or refused request, where no page ever arrives.
     */
    public synchronized void markSettled(int requestId) {
        if (requestId < 0) {
            // The server could not read the request id (malformed payload): the
            // error is still surfaced to the player, but it must not settle
            // whichever request is currently pending.
            return;
        }
        if (requestId != lastRequestId) {
            return; // stale status for a superseded request
        }
        settled = true;
        if (pendingRequestId == requestId) {
            pendingRequestId = -1;
        }
        revision++;
    }

    public synchronized void clear() {
        events.clear();
        lastRequestId = -1;
        lastTotal = 0;
        pages = 0;
        truncated = false;
        settled = false;
        pendingRequestId = -1;
        playheadMs = Long.MAX_VALUE;
        revision++;
    }

    public synchronized int eventCount() {
        return events.size();
    }

    public synchronized int expectedTotal() {
        return lastTotal;
    }

    public synchronized int pages() {
        return pages;
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    public synchronized int requestId() {
        return lastRequestId;
    }

    public synchronized boolean isComplete() {
        return settled || (lastTotal > 0 && events.size() >= lastTotal);
    }

    public synchronized long revision() {
        return revision;
    }

    /**
     * Events the renderer should consider at the playhead. In
     * {@link Mode#DIFF} only the window ending at the playhead is returned;
     * otherwise every event up to the playhead (the renderer reconstructs the
     * last-writer-wins world state from these).
     */
    public synchronized List<BlockEvent> visibleAtPlayhead() {
        if (events.isEmpty()) {
            return new ArrayList<>();
        }
        long head = playhead();
        long from = mode == Mode.DIFF ? head - diffWindowMs : Long.MIN_VALUE;
        List<BlockEvent> out = new ArrayList<>();
        for (BlockEvent e : events) {
            if (e.t > head) {
                break;
            }
            if (e.t >= from) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized long minTime() {
        return events.isEmpty() ? 0 : events.get(0).t;
    }

    public synchronized long maxTime() {
        return events.isEmpty() ? 0 : events.get(events.size() - 1).t;
    }

    public synchronized long playhead() {
        if (playheadMs == Long.MAX_VALUE) {
            return maxTime();
        }
        return playheadMs;
    }

    public synchronized void setPlayhead(long ms) {
        if (events.isEmpty()) {
            playheadMs = Long.MAX_VALUE;
            return;
        }
        long lo = minTime();
        long hi = maxTime();
        this.playheadMs = Math.max(lo, Math.min(hi, ms));
    }

    /** Normalized 0..1 playhead for the slider. */
    public synchronized double playheadFraction() {
        if (events.isEmpty()) {
            return 1.0;
        }
        long lo = minTime();
        long hi = maxTime();
        if (hi <= lo) {
            return 1.0;
        }
        return (double) (playhead() - lo) / (double) (hi - lo);
    }

    public synchronized void setPlayheadFraction(double f) {
        if (events.isEmpty()) {
            return;
        }
        long lo = minTime();
        long hi = maxTime();
        setPlayhead(lo + (long) ((hi - lo) * Math.max(0.0, Math.min(1.0, f))));
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized void setMode(Mode mode) {
        if (mode != null) {
            this.mode = mode;
            revision++;
        }
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized void setPlaying(boolean playing) {
        // Pressing play while the playhead sits at the end (the default while the
        // stream is pinned to "show everything") should replay from the start,
        // not stop instantly on the first tick.
        if (playing && !events.isEmpty()
                && (playheadMs == Long.MAX_VALUE || playhead() >= maxTime())) {
            playheadMs = minTime();
        }
        this.playing = playing;
    }

    /** Playback speed multiplier (timeline seconds per real second). */
    public synchronized double speed() {
        return speed;
    }

    public synchronized void setSpeed(double speed) {
        this.speed = Math.max(0.1, Math.min(256.0, speed));
    }

    /** Lookback window used by {@link Mode#DIFF}, in seconds. */
    public synchronized double diffWindowSeconds() {
        return diffWindowMs / 1000.0;
    }

    public synchronized void setDiffWindowSeconds(double seconds) {
        this.diffWindowMs = (long) Math.max(1.0, Math.min(3600.0, seconds)) * 1000L;
        revision++;
    }

    public synchronized long diffWindowMs() {
        return diffWindowMs;
    }

    /** Advance the playhead when playing (called once per client tick). */
    public synchronized void tick(double deltaSeconds) {
        if (!playing || events.isEmpty()) {
            return;
        }
        long lo = minTime();
        long hi = maxTime();
        long next = playhead() + (long) (deltaSeconds * speed() * 1000.0);
        if (next >= hi) {
            next = hi;
            playing = false;
        }
        if (next < lo) {
            next = lo;
        }
        playheadMs = next;
    }

    /** The request id the client is currently waiting on (clears on ingest). */
    public synchronized void setRequestId(int requestId) {
        this.pendingRequestId = requestId;
        this.events.clear();
        this.lastRequestId = requestId;
        this.lastTotal = 0;
        this.pages = 0;
        this.truncated = false;
        this.settled = false;
        this.playheadMs = Long.MAX_VALUE;
        revision++;
    }

    public synchronized int pendingRequestId() {
        return pendingRequestId;
    }

    public synchronized List<BlockEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
