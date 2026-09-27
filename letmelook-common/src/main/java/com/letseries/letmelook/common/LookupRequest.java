package com.letseries.letmelook.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -&gt; Server lookup request. Mirrors CoreProtect query semantics
 * (radius / time / user / action) so CO users feel at home.
 */
public final class LookupRequest {
    public String world;
    public int[] min = new int[3];
    public int[] max = new int[3];
    public long timeStart;
    public long timeEnd;
    public List<String> users = new ArrayList<>();
    public List<String> actions = new ArrayList<>();
    public int page;
    public int limit = LetMeLookProtocol.PAGE_LIMIT;
    public int requestId;

    public LookupRequest() {}
}
