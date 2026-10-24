package com.letseries.letmelook.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wire codec: adaptive gzip framing, kind routing and W1/W2 compatibility. */
class CodecTest {

    private static TimelineChunk chunk(int requestId, int eventCount) {
        TimelineChunk c = new TimelineChunk();
        c.requestId = requestId;
        c.page = 0;
        c.pages = 1;
        c.total = eventCount;
        c.events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            c.events.add(new BlockEvent(1_700_000_000_000L + i, i, 64, -i,
                    "minecraft:air", "minecraft:oak_stairs[facing=north]", "Steve", ActionFilter.PLACE));
        }
        return c;
    }

    @Test
    @DisplayName("small payloads stay plain JSON so W1 servers keep working")
    void smallPayloadIsNotCompressed() throws Exception {
        TimelineChunk in = chunk(7, 3);
        byte[] payload = Codec.encodeAdaptive(in);

        assertFalse(Codec.isCompressed(payload));
        assertEquals(LetMeLookProtocol.KIND_TIMELINE, Codec.peekKind(payload));

        TimelineChunk out = Codec.decodeAdaptive(payload, TimelineChunk.class);
        assertEquals(7, out.requestId);
        assertEquals(3, out.events.size());
        assertEquals("minecraft:oak_stairs[facing=north]", out.events.get(0).to);
    }

    @Test
    @DisplayName("large payloads are gzipped and round-trip through unwrap")
    void largePayloadIsCompressed() throws Exception {
        TimelineChunk in = chunk(9, 4000);
        byte[] payload = Codec.encodeAdaptive(in);

        assertTrue(Codec.isCompressed(payload), "4000 events must exceed the 32 KiB threshold");
        assertTrue(payload.length < Codec.encode(in).length, "gzip must actually shrink the page");

        assertEquals(LetMeLookProtocol.KIND_TIMELINE, Codec.peekKindAdaptive(payload));
        TimelineChunk out = Codec.decodeAdaptive(payload, TimelineChunk.class);
        assertEquals(4000, out.events.size());
        assertEquals(1_700_000_003_999L, out.events.get(3999).t);
    }

    @Test
    @DisplayName("unwrap inflates exactly once and yields plain JSON")
    void unwrapIsIdempotentForPlainInput() throws Exception {
        byte[] raw = Codec.encode(chunk(1, 1));
        assertEquals(new String(raw, StandardCharsets.UTF_8), new String(Codec.unwrap(raw), StandardCharsets.UTF_8));

        byte[] gz = Codec.compress(raw);
        assertEquals(new String(raw, StandardCharsets.UTF_8), new String(Codec.unwrap(gz), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a status object is routed by its kind field")
    void statusRoundTrip() throws Exception {
        LookupStatus status = LookupStatus.of(42, LookupStatus.State.THROTTLED, "cooldown");
        status.retryAfterMs = 1200L;
        byte[] payload = Codec.encodeAdaptive(status);

        assertEquals(LetMeLookProtocol.KIND_STATUS, Codec.peekKind(payload));

        LookupStatus out = Codec.decodeAdaptive(payload, LookupStatus.class);
        assertEquals(42, out.requestId);
        assertEquals(LookupStatus.State.THROTTLED, out.state);
        assertEquals(1200L, out.retryAfterMs);
        assertEquals(LetMeLookProtocol.PROTOCOL_VERSION, out.protocol);
    }

    @Test
    @DisplayName("a W2 payload with no kind field decodes as a timeline page")
    void missingKindMeansTimeline() {
        String json = "{\"requestId\":3,\"page\":0,\"total\":1,\"events\":["
                + "{\"t\":5,\"x\":1,\"y\":2,\"z\":3,\"from\":\"minecraft:air\","
                + "\"to\":\"minecraft:dirt\",\"user\":\"Alex\",\"action\":1}]}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);

        assertNull(Codec.peekKind(raw), "absent kind must be null, not a guess");

        TimelineChunk decoded = Codec.decode(raw, TimelineChunk.class);
        assertEquals(LetMeLookProtocol.KIND_TIMELINE, decoded.kind, "field default must apply");
        assertEquals(1, decoded.events.size());
        assertEquals("minecraft:dirt", decoded.events.get(0).to);
        assertFalse(decoded.truncated);
    }

    @Test
    @DisplayName("peekKind stops at the header and tolerates a broken tail")
    void peekKindReadsOnlyTheHeader() {
        String json = "{\"kind\":\"status\",\"state\":\"DONE\",\"junk\":";
        assertEquals(LetMeLookProtocol.KIND_STATUS, Codec.peekKind(json.getBytes(StandardCharsets.UTF_8)));

        String kindLater = "{\"pad\":\"x\",\"kind\":\"timeline\",\"events\":[]}";
        assertEquals(LetMeLookProtocol.KIND_TIMELINE, Codec.peekKind(kindLater.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("garbage input is rejected instead of throwing from peekKind")
    void garbageIsHandled() {
        assertNull(Codec.peekKind(null));
        assertNull(Codec.peekKind(new byte[0]));
        assertNull(Codec.peekKind("not json at all".getBytes(StandardCharsets.UTF_8)));
        assertNull(Codec.peekKind("[1,2,3]".getBytes(StandardCharsets.UTF_8)));
        assertNull(Codec.peekKind("{\"kind\":12}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,
                () -> Codec.decode("{{{".getBytes(StandardCharsets.UTF_8), TimelineChunk.class));
    }

    @Test
    @DisplayName("page bookkeeping survives the wire")
    void pageMetadataRoundTrips() throws Exception {
        TimelineChunk c = new TimelineChunk();
        c.requestId = 11;
        c.page = 2;
        c.pages = 3;
        c.total = 25_000;
        c.truncated = true;

        TimelineChunk out = Codec.decodeAdaptive(Codec.encodeAdaptive(c), TimelineChunk.class);
        assertEquals(2, out.page);
        assertEquals(3, out.pages);
        assertEquals(25_000, out.total);
        assertTrue(out.truncated);
        assertTrue(out.isLastPage());
    }

    @Test
    @DisplayName("isLastPage is false when an older server did not report the page count")
    void lastPageUnknownForOlderServers() {
        TimelineChunk c = new TimelineChunk();
        c.page = 0;
        assertFalse(c.isLastPage());
    }
}
