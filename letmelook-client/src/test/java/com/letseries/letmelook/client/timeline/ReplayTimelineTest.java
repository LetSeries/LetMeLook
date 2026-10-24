package com.letseries.letmelook.client.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letseries.letmelook.common.BlockEvent;
import com.letseries.letmelook.common.TimelineChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Timeline ingest and playback semantics. {@link ReplayTimeline} has no
 * Minecraft dependency, so these run as plain unit tests.
 */
class ReplayTimelineTest {

    private static TimelineChunk page(int requestId, int page, int pages, int total, long... times) {
        TimelineChunk chunk = new TimelineChunk();
        chunk.requestId = requestId;
        chunk.page = page;
        chunk.pages = pages;
        chunk.total = total;
        for (int i = 0; i < times.length; i++) {
            chunk.events.add(new BlockEvent(times[i], i, 64, 0,
                    "minecraft:air", "minecraft:stone", "Steve", 1));
        }
        return chunk;
    }

    @Test
    @DisplayName("pages accumulate, sort by time and stay pinned to the newest event")
    void ingestAccumulatesSortedEvents() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(5);
        timeline.ingest(page(5, 0, 2, 4, 3000L, 1000L));
        timeline.ingest(page(5, 1, 2, 4, 4000L, 2000L));

        assertEquals(4, timeline.eventCount());
        assertEquals(4, timeline.expectedTotal());
        assertEquals(2, timeline.pages());
        assertTrue(timeline.isComplete());

        List<BlockEvent> snapshot = timeline.snapshot();
        assertEquals(1000L, snapshot.get(0).t);
        assertEquals(4000L, snapshot.get(3).t);

        assertEquals(1000L, timeline.minTime());
        assertEquals(4000L, timeline.maxTime());
        assertEquals(4000L, timeline.playhead(), "streaming pages must not leave the playhead behind");
        assertEquals(1.0, timeline.playheadFraction(), 1e-9);
    }

    @Test
    @DisplayName("a page from a superseded request never touches the new timeline")
    void stalePageIsIgnored() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        timeline.ingest(page(1, 0, 1, 1, 1000L));
        assertEquals(1, timeline.eventCount());

        timeline.setRequestId(2);
        assertEquals(0, timeline.eventCount());
        assertEquals(0, timeline.expectedTotal());

        timeline.ingest(page(1, 0, 1, 1, 5000L));
        assertEquals(0, timeline.eventCount(), "W2 cleared the timeline here");
        assertEquals(2, timeline.pendingRequestId());

        timeline.ingest(page(2, 0, 1, 1, 7000L));
        assertEquals(1, timeline.eventCount());
        assertEquals(7000L, timeline.maxTime());
    }

    @Test
    @DisplayName("setRequestId resets all derived state")
    void setRequestIdResetsState() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        TimelineChunk truncated = page(1, 0, 1, 10, 100L);
        truncated.truncated = true;
        timeline.ingest(truncated);
        assertTrue(timeline.truncated());
        assertEquals(10, timeline.expectedTotal());

        timeline.setRequestId(2);
        assertEquals(0, timeline.eventCount());
        assertEquals(0, timeline.expectedTotal());
        assertEquals(0, timeline.pages());
        assertFalse(timeline.truncated());
        assertFalse(timeline.isComplete());
    }

    @Test
    @DisplayName("a terminal status settles even an empty or refused request")
    void markSettledCompletesEmptyRequest() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(3);
        assertFalse(timeline.isComplete());

        timeline.markSettled(3);
        assertTrue(timeline.isComplete(), "no page ever arrives for a refused request");
        assertEquals(-1, timeline.pendingRequestId());
        assertEquals(0, timeline.eventCount());

        timeline.setRequestId(4);
        timeline.markSettled(3);
        assertFalse(timeline.isComplete(), "a status for an old request must not settle the new one");
        timeline.markSettled(4);
        assertTrue(timeline.isComplete());
    }

    @Test
    @DisplayName("a status with no readable request id never settles the pending request")
    void malformedStatusDoesNotSettle() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(8);
        timeline.markSettled(-1);

        assertFalse(timeline.isComplete(), "the server could not name the request, so nothing is settled");
        assertEquals(8, timeline.pendingRequestId(), "the real request is still in flight");
    }

    @Test
    @DisplayName("DIFF mode limits events to the window ending at the playhead")
    void diffModeLimitsToWindow() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        timeline.ingest(page(1, 0, 1, 4, 0L, 1000L, 2000L, 3000L));

        timeline.setDiffWindowSeconds(1.0);
        assertEquals(1.0, timeline.diffWindowSeconds(), 1e-9);
        timeline.setMode(ReplayTimeline.Mode.DIFF);
        timeline.setPlayhead(3000L);

        List<BlockEvent> window = timeline.visibleAtPlayhead();
        assertEquals(2, window.size());
        assertEquals(2000L, window.get(0).t);
        assertEquals(3000L, window.get(1).t);

        timeline.setMode(ReplayTimeline.Mode.REPLAY);
        assertEquals(4, timeline.visibleAtPlayhead().size());

        timeline.setMode(ReplayTimeline.Mode.DIFF);
        timeline.setPlayhead(1000L);
        assertEquals(2, timeline.visibleAtPlayhead().size(), "0 and 1000 fall inside the window");
    }

    @Test
    @DisplayName("diff window is clamped to a sane range")
    void diffWindowIsClamped() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setDiffWindowSeconds(0.01);
        assertEquals(1000L, timeline.diffWindowMs());
        timeline.setDiffWindowSeconds(999_999.0);
        assertEquals(3_600_000L, timeline.diffWindowMs());
    }

    @Test
    @DisplayName("play rewinds from the end, advances by speed, and stops there")
    void playRewindsAdvancesAndStops() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        timeline.ingest(page(1, 0, 1, 3, 0L, 1000L, 2000L));
        timeline.setSpeed(4.0);

        timeline.setPlaying(true);
        assertTrue(timeline.isPlaying());
        assertEquals(0L, timeline.playhead(), "playing from the end must replay from the start");

        timeline.tick(0.25);
        assertEquals(1000L, timeline.playhead());
        assertTrue(timeline.isPlaying());

        timeline.tick(0.25);
        assertEquals(2000L, timeline.playhead());
        assertFalse(timeline.isPlaying(), "playback stops at the last event");

        timeline.tick(0.25);
        assertEquals(2000L, timeline.playhead(), "a stopped timeline does not advance");
    }

    @Test
    @DisplayName("playhead fractions clamp to the data range")
    void playheadFractionsClamp() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        timeline.ingest(page(1, 0, 1, 3, 1000L, 2000L, 3000L));

        timeline.setPlayheadFraction(0.5);
        assertEquals(2000L, timeline.playhead());
        assertEquals(0.5, timeline.playheadFraction(), 1e-9);

        timeline.setPlayheadFraction(-2.0);
        assertEquals(1000L, timeline.playhead());
        assertEquals(0.0, timeline.playheadFraction(), 1e-9);

        timeline.setPlayheadFraction(9.0);
        assertEquals(3000L, timeline.playhead());
        assertEquals(1.0, timeline.playheadFraction(), 1e-9);
    }

    @Test
    @DisplayName("setPlayhead clamps to the data range")
    void setPlayheadClamps() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        timeline.ingest(page(1, 0, 1, 2, 100L, 200L));

        timeline.setPlayhead(50L);
        assertEquals(100L, timeline.playhead());
        timeline.setPlayhead(999L);
        assertEquals(200L, timeline.playhead());
    }

    @Test
    @DisplayName("the truncation flag reported by the server is retained")
    void truncatedFlagPropagates() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        TimelineChunk chunk = page(1, 0, 1, 10, 1L);
        chunk.truncated = true;
        timeline.ingest(chunk);

        assertTrue(timeline.truncated());
        assertEquals(10, timeline.expectedTotal());
        assertFalse(timeline.isComplete(), "10 of the events the server wanted to send is still loading");
    }

    @Test
    @DisplayName("clear resets everything")
    void clearResets() {
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.setRequestId(1);
        timeline.ingest(page(1, 0, 1, 1, 1L));
        timeline.clear();

        assertEquals(0, timeline.eventCount());
        assertEquals(-1, timeline.requestId());
        assertEquals(-1, timeline.pendingRequestId());
        assertEquals(0L, timeline.minTime());
        assertEquals(0L, timeline.maxTime());
        assertFalse(timeline.isComplete());
    }

    @Test
    @DisplayName("every mutation bumps the revision used by render caches")
    void revisionTracksMutations() {
        ReplayTimeline timeline = new ReplayTimeline();
        long start = timeline.revision();
        timeline.setRequestId(1);
        long afterRequest = timeline.revision();
        assertTrue(afterRequest > start);

        timeline.ingest(page(1, 0, 1, 1, 1L));
        long afterIngest = timeline.revision();
        assertTrue(afterIngest > afterRequest);

        timeline.setMode(ReplayTimeline.Mode.DIFF);
        assertTrue(timeline.revision() > afterIngest);
    }
}
