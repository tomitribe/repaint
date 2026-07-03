package org.tomitribe.repaint.two;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Wrap {

    /**
     * The stairs: one line wider than your terminal breaks the whole block.
     *
     * The painter's accounting is in LOGICAL lines, but the terminal wraps a
     * too-long line onto two PHYSICAL rows. The block below prints three
     * logical lines, one of them --width characters wide; if that exceeds
     * your terminal width it occupies four rows, cursor-up three only climbs
     * part of the way back, and every frame the block staircases down your
     * screen leaving a trail. This is why every fixed-block renderer
     * truncates to the terminal width BEFORE printing (compose iterates
     * truncation until every line fits, tty.go:415-433) — and why it must
     * know the terminal width at all, which is chapter 03.
     *
     * @param width Width of the middle line; anything wider than your
     *              terminal triggers the stairs, anything narrower is calm
     * @param frames How many frames to paint before stopping the damage
     */
    @Command
    public void wrap(@Out final PrintStream out,
                     @Option("width") @Default("200") final int width,
                     @Option("frames") @Default("20") final int frames) throws InterruptedException {

        int numLines = 0;
        for (int frame = 1; frame <= frames; frame++) {
            final String[] lines = {
                    "top line     (frame " + frame + ")",
                    "wrap ".repeat(Math.max(width / 5, 1)).substring(0, width),
                    "bottom line  (frame " + frame + ")",
            };

            if (numLines > 0) {
                out.printf("\u001B[%dA", numLines); // ESC[nA: cursor up n rows, same column
            }
            out.print('\r'); // \r: cursor to column 0, same row
            for (final String line : lines) {
                out.println(line);
            }
            numLines = lines.length; // logical count — the physical count is what the terminal saw
            out.flush();
            Thread.sleep(150);
        }
    }
}
