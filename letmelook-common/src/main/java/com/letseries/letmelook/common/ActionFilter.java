package com.letseries.letmelook.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code actions} field of a {@link LookupRequest} (the wire uses the
 * CoreProtect action ids {@code 0}/{@code 1}, the friendly names
 * {@code break}/{@code place}, or a mix) into the integer list CoreProtect's
 * {@code performLookup} expects.
 *
 * <p>Lives in {@code common} because these are wire values shared by both
 * sides; unknown or empty input degrades to "break + place" rather than an
 * empty filter, which would silently return nothing.
 */
public final class ActionFilter {
    /** CoreProtect action id: block removed. */
    public static final int BREAK = 0;
    /** CoreProtect action id: block placed. */
    public static final int PLACE = 1;

    /** Both actions, in CoreProtect's order. */
    public static final List<Integer> DEFAULT = List.of(BREAK, PLACE);

    private ActionFilter() {}

    /** Case-insensitive; never returns an empty list. */
    public static List<Integer> parse(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return DEFAULT;
        }
        List<Integer> out = new ArrayList<>(2);
        for (String action : actions) {
            if (action == null) {
                continue;
            }
            String s = action.trim().toLowerCase(Locale.ROOT);
            if (s.equals("break") || s.equals("0")) {
                add(out, BREAK);
            } else if (s.equals("place") || s.equals("1")) {
                add(out, PLACE);
            }
        }
        return out.isEmpty() ? DEFAULT : out;
    }

    private static void add(List<Integer> out, int action) {
        if (!out.contains(action)) {
            out.add(action);
        }
    }

    /** Wire name for an action id ({@code "break"}/{@code "place"}), or {@code null}. */
    public static String nameOf(int actionId) {
        return switch (actionId) {
            case BREAK -> "break";
            case PLACE -> "place";
            default -> null;
        };
    }
}
