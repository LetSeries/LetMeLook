package com.letseries.letmelook.common;

import java.util.ArrayList;
import java.util.List;

/** Server -&gt; Client paged timeline payload. */
public final class TimelineChunk {
    public int requestId;
    public int page;
    public int total;
    public List<BlockEvent> events = new ArrayList<>();

    public TimelineChunk() {}
}
