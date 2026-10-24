package com.letseries.letmelook.client.selection;

import java.util.Locale;

/**
 * Client-side cuboid selection (pos1/pos2) plus the dimension each corner was
 * captured in.
 *
 * <p>W2 stored only coordinates, so a region selected in the overworld silently
 * "worked" in the nether and {@code /lm status} could not say which dimension
 * the box belonged to. Corners now remember their dimension and capturing a
 * corner in a different dimension resets the other one, so a mixed-dimension
 * cuboid cannot be formed.
 */
public final class SelectionState {
    private int[] pos1;
    private String pos1Dim;
    private int[] pos2;
    private String pos2Dim;

    /**
     * @return true when the previous opposite corner belonged to a different
     *         dimension and was therefore cleared.
     */
    public boolean setPos1(int x, int y, int z, String dimension) {
        boolean reset = pos2 != null && dimension != null && pos2Dim != null && !dimension.equals(pos2Dim);
        if (reset) {
            pos2 = null;
            pos2Dim = null;
        }
        this.pos1 = new int[] {x, y, z};
        this.pos1Dim = dimension;
        return reset;
    }

    /**
     * @return true when the previous opposite corner belonged to a different
     *         dimension and was therefore cleared.
     */
    public boolean setPos2(int x, int y, int z, String dimension) {
        boolean reset = pos1 != null && dimension != null && pos1Dim != null && !dimension.equals(pos1Dim);
        if (reset) {
            pos1 = null;
            pos1Dim = null;
        }
        this.pos2 = new int[] {x, y, z};
        this.pos2Dim = dimension;
        return reset;
    }

    public boolean hasSelection() {
        return pos1 != null && pos2 != null;
    }

    public int[] pos1() {
        return pos1 == null ? null : pos1.clone();
    }

    public int[] pos2() {
        return pos2 == null ? null : pos2.clone();
    }

    /** Dimension of the captured corners, or {@code null} when nothing is selected. */
    public String dimension() {
        return pos1Dim != null ? pos1Dim : pos2Dim;
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

    /** Per-axis extent (always >= 1) once both corners exist. */
    public int[] size() {
        int[] min = min();
        int[] max = max();
        return new int[] {max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1};
    }

    public long volume() {
        if (!hasSelection()) {
            return 0L;
        }
        int[] s = size();
        return (long) s[0] * s[1] * s[2];
    }

    public void clear() {
        pos1 = null;
        pos1Dim = null;
        pos2 = null;
        pos2Dim = null;
    }

    /** Compact {@code min -> max} description for chat / HUD. */
    public String describe() {
        if (pos1 == null && pos2 == null) {
            return "none";
        }
        if (!hasSelection()) {
            int[] only = pos1 != null ? pos1 : pos2;
            String dim = dimension();
            return (pos1 != null ? "pos1" : "pos2") + "=" + fmt(only)
                    + (dim == null ? "" : " @" + shortDim(dim)) + " (need the other corner)";
        }
        int[] s = size();
        return fmt(min()) + " -> " + fmt(max()) + " [" + s[0] + "x" + s[1] + "x" + s[2]
                + " = " + volume() + " blocks]"
                + (dimension() == null ? "" : " @" + shortDim(dimension()));
    }

    /** {@code minecraft:overworld} -> {@code overworld}. */
    public static String shortDim(String dimension) {
        if (dimension == null) {
            return "?";
        }
        int colon = dimension.indexOf(':');
        return (colon >= 0 ? dimension.substring(colon + 1) : dimension).toLowerCase(Locale.ROOT);
    }

    private static String fmt(int[] p) {
        return "(" + p[0] + "," + p[1] + "," + p[2] + ")";
    }
}
