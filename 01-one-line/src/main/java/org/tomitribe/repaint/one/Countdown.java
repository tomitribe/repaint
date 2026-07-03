package org.tomitribe.repaint.one;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Countdown {

    /**
     * The chapter's own defect, on purpose: watch the leftover "s" at zero.
     *
     * A countdown SHRINKS ("10.0s" is one character wider than "9.9s"), and
     * '\r' + overwrite never erases — the orphaned character from the longer
     * frame stays on screen. Run without --pad to see it; with --pad the line
     * is right-padded to a fixed width so the leftover is overwritten with
     * spaces. Overwrite-with-padding (not erase-then-write) is the fix every
     * surveyed renderer uses.
     *
     * @param fromSeconds Seconds to count down from
     * @param pad Right-pad every frame to a fixed width so shorter frames
     *            overwrite what longer frames left behind
     */
    @Command
    public void countdown(@Out final PrintStream out,
                          @Option("from") @Default("10") final int fromSeconds,
                          @Option("pad") final boolean pad) throws InterruptedException {
        final int width = ("T-minus " + fromSeconds + ".0s").length();
        for (long tenths = fromSeconds * 10L; tenths >= 0; tenths--) {
            String line = String.format("T-minus %.1fs", tenths / 10d);
            if (pad) {
                line = String.format("%-" + width + "s", line);
            }
            out.print('\r' + line);
            out.flush();
            Thread.sleep(100);
        }
        out.println();
    }
}
