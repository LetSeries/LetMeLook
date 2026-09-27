package com.letseries.letmelook.client.selection;

/** Client-side cuboid selection (pos1/pos2), rendered as highlight box in W2. */
public final class SelectionState {
    private int[] pos1;
    private int[] pos2;

    public void setPos1(int x, int y, int z) {
        this.pos1 = new int[] {x, y, z};
    }

    public void setPos2(int x, int y, int z) {
        this.pos2 = new int[] {x, y, z};
    }

    public boolean hasSelection() {
        return pos1 != null && pos2 != null;
    }

    public int[] min() {
        return new int[] {
            Math.min(pos1[0], pos2[0]), Math.min(pos1[1], pos2[1]), Math.min(pos1[2], pos2[2])
        };
    }

    public int[] max() {
        return new int[] {
            Math.max(pos1[0], pos2[0]), Math.max(pos1[1], pos2[1]), Math.max(pos1[2], pos2[2])
        };
    }
}
