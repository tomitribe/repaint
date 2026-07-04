package org.tomitribe.repaint.five;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Golden frames, down to the byte. Because SmoothRenderer.frame() is a
 * pure function from (lines, previous count, width) to the exact bytes a
 * terminal will receive, we can pin the whole escape choreography in
 * assertEquals — including the landmines: no ESC[0A on the first frame,
 * and wipe rows counted when the block shrinks.
 */
public class FrameTest {

    private static final String ESC = "\u001B";

    @Test
    public void firstFrameClimbsZeroRows() {
        final String frame = SmoothRenderer.frame(List.of("ab"), 0, 4);
        assertEquals(
                ESC + "[?2026h"     // begin synchronized update
                        + "\r"          // no ESC[nA: previous count is 0 and ESC[0A would mean "up 1"
                        + "ab  \n"      // padded to width
                        + ESC + "[?2026l",
                frame);
        assertFalse(frame.contains("[0A"), "ESC[0A must never be emitted");
    }

    @Test
    public void laterFramesClimbThePreviousCount() {
        final String frame = SmoothRenderer.frame(List.of("x"), 2, 3);
        assertEquals(
                ESC + "[?2026h"
                        + ESC + "[2A"   // climb over the previous frame's two lines
                        + "\r"
                        + "x  \n"
                        + "   \n"       // wipe: the row the previous frame used and this one doesn't
                        + ESC + "[?2026l",
                frame);
    }

    @Test
    public void wideGlyphsFitByCells() {
        // chapter 04 riding along: デ is 2 cells, so 4 cells of name + 0 spaces
        final String frame = SmoothRenderer.frame(List.of("デプ"), 0, 4);
        assertEquals(
                ESC + "[?2026h" + "\r"
                        + "デプ\n"
                        + ESC + "[?2026l",
                frame);
    }

    @Test
    public void cursorHiddenForTheLifetimeNotPerFrame() {
        // hide/show live OUTSIDE the frame: hiding per frame parks a
        // blinking cursor below the block between frames (field-tested
        // on Apple Terminal; compose has the same artifact)
        final String frame = SmoothRenderer.frame(List.of("x"), 1, 1);
        assertFalse(frame.contains("?25"), "no cursor hide/show inside a frame");
        assertEquals(ESC + "[?25l", SmoothRenderer.HIDE);
        assertEquals(ESC + "[?25h", SmoothRenderer.SHOW);
    }
}
