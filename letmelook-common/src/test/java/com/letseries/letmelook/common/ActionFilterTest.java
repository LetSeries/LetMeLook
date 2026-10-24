package com.letseries.letmelook.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wire action-string parsing shared by the server filter and the client UI. */
class ActionFilterTest {

    @Test
    @DisplayName("empty/unknown input degrades to break+place, never an empty filter")
    void defaultsToBothActions() {
        assertEquals(ActionFilter.DEFAULT, ActionFilter.parse(null));
        assertEquals(ActionFilter.DEFAULT, ActionFilter.parse(List.of()));
        assertEquals(ActionFilter.DEFAULT, ActionFilter.parse(List.of("nonsense")));
        assertEquals(ActionFilter.DEFAULT, ActionFilter.parse(java.util.Arrays.asList((String) null)));
    }

    @Test
    @DisplayName("names and ids are accepted case-insensitively")
    void acceptsNamesAndIds() {
        assertEquals(List.of(ActionFilter.PLACE), ActionFilter.parse(List.of("PLACE")));
        assertEquals(List.of(ActionFilter.BREAK), ActionFilter.parse(List.of("break")));
        assertEquals(List.of(ActionFilter.BREAK), ActionFilter.parse(List.of(" 0 ")));
        assertEquals(List.of(ActionFilter.PLACE), ActionFilter.parse(List.of("1")));
    }

    @Test
    @DisplayName("duplicates collapse and order follows the input")
    void collapsesDuplicates() {
        assertEquals(List.of(ActionFilter.BREAK, ActionFilter.PLACE),
                ActionFilter.parse(List.of("break", "place", "break", "0")));
        assertEquals(List.of(ActionFilter.PLACE, ActionFilter.BREAK),
                ActionFilter.parse(List.of("place", "break")));
    }

    @Test
    @DisplayName("nameOf maps ids back to wire names")
    void nameOfRoundTrip() {
        assertEquals("break", ActionFilter.nameOf(ActionFilter.BREAK));
        assertEquals("place", ActionFilter.nameOf(ActionFilter.PLACE));
        assertNull(ActionFilter.nameOf(2));
    }
}
