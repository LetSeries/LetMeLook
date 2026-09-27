package com.letseries.letmelook.common;

/**
 * One block change event. Block states are stored as
 * {@code NamespacedId + properties} strings (never numeric ids)
 * so 1.21.x state changes stay compatible.
 *
 * <p>action: 0 = break, 1 = place (aligned with CoreProtect action ids).
 */
public final class BlockEvent {
    /** epoch seconds */
    public long t;
    public int x;
    public int y;
    public int z;
    public String from = "minecraft:air";
    public String to = "minecraft:stone";
    public String user = "";
    public int action = 1;

    public BlockEvent() {}

    public BlockEvent(long t, int x, int y, int z, String from, String to, String user, int action) {
        this.t = t;
        this.x = x;
        this.y = y;
        this.z = z;
        this.from = from;
        this.to = to;
        this.user = user;
        this.action = action;
    }
}
