package org.tomitribe.repaint.six.jline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The invariant under test: block rows + one resting row for the cursor
 * never exceed the window height — so the block can never be the thing
 * that scrolls the screen.
 */
public class ClampTest {

    @Test
    public void fitsUntouched() {
        final List<String> lines = List.of("h", "a", "b", "c");
        assertEquals(lines, SmoothRenderer.clamp(lines, 10));
    }

    @Test
    public void exactFitUntouched() {
        // 5 lines + 1 resting row = height 6: still no clamp needed
        final List<String> lines = List.of("h", "a", "b", "c", "d");
        assertEquals(lines, SmoothRenderer.clamp(lines, 6));
    }

    @Test
    public void overflowCollapsesIntoMore() {
        final List<String> lines = List.of("h", "a", "b", "c", "d", "e", "f", "g", "i", "j");
        // height 6 → at most 5 rows: 4 content lines + the "more" line
        assertEquals(
                List.of("h", "a", "b", "c", "… 6 more"),
                SmoothRenderer.clamp(lines, 6));
    }

    @Test
    public void tinyWindowStillShowsSomething() {
        final List<String> lines = List.of("h", "a", "b");
        assertEquals(
                List.of("h", "… 2 more"),
                SmoothRenderer.clamp(lines, 2));
    }
}
