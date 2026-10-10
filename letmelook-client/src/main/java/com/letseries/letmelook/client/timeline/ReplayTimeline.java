package com.letseries.letmelook.client.timeline;

import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * W2 in-memory timeline: accumulates paged {@link TimelineChunk}s, keeps them
 * time-sorted, and exposes a playhead (epoch millis) used by the ghost
 * renderer and the time slider.
 *
 * <p>Playback modes (see {@code /lm mode}): REPLAY applies all events with
 * {@code t <= playhead}; DIFF applies only the delta at the playhead window.
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

    /** Playhead in epoch millis; {@link Long#MAX_VALUE} = show everything. */
    private long playheadMs = Long.MAX_VALUE;
    private Mode mode = Mode.REPLAY;
    private boolean playing = false;
    private double speed = 4.0;
    private int pendingRequestId = -1;

    public synchronized void ingest(TimelineChunk chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.requestId != lastRequestId) {
            events.clear();
            lastRequestId = chunk.requestId;
            playheadMs = Long.MAX_VALUE;
        }
        lastTotal = chunk.total;
        if (chunk.events != null) {
            events.addAll(chunk.events);
        }
        events.sort((a, b) -> Long.compare(a.t, b.t));
        if (!events.isEmpty() && playheadMs == Long.MAX_VALUE) {
            playheadMs = events.get(events.size() - 1).t;
        }
    }

    public synchronized void clear() {
        events.clear();
        lastRequestId = -1;
        lastTotal = 0;
        playheadMs = Long.MAX_VALUE;
    }

    public synchronized int eventCount() {
        return events.size();
    }

    public synchronized int expectedTotal() {
        return lastTotal;
    }

    public synchronized int requestId() {
        return lastRequestId;
    }

    public synchronized boolean isComplete() {
        return lastTotal > 0 && events.size() >= lastTotal;
    }

    /** Events with {@code t <= playhead} (what the ghost layer renders). */
    public synchronized List<BlockEvent> visibleAtPlayhead() {
        if (playheadMs == Long.MAX_VALUE) {
            return new ArrayList<>(events);
        }
        List<BlockEvent> out = new ArrayList<>();
        for (BlockEvent e : events) {
            if (e.t <= playheadMs) {
                out.add(e);
            } else {
                break;
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
        long lo = minTime();
        long hi = maxTime();
        if (events.isEmpty()) {
            playheadMs = Long.MAX_VALUE;
            return;
        }
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
        this.mode = mode;
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized void setPlaying(boolean playing) {
        this.playing = playing;
    }

    /** Playback speed multiplier (timeline seconds per real second). */
    public synchronized double speed() {
        return speed;
    }

    public synchronized void setSpeed(double speed) {
        this.speed = Math.max(0.1, Math.min(256.0, speed));
    }

    /** Advance the playhead when playing (called once per client tick). */
    public synchronized void tick(double deltaSeconds) {
        if (!playing || events.isEmpty()) {
            return;
        }
        long lo = minTime();
        long hi = maxTime();
        long cur = playhead();
        long next = cur + (long) (deltaSeconds * speed() * 1000.0);
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
        this.playheadMs = Long.MAX_VALUE;
    }

    public synchronized int pendingRequestId() {
        return pendingRequestId;
    }

    public synchronized List<BlockEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
