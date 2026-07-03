package org.tomitribe.repaint.two;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Eat {

    /**
     * The first-frame landmine: watch this command eat your scrollback.
     *
     * A broken painter moves up by the CURRENT frame's line count instead of
     * the PREVIOUS frame's. On frame one nothing has been printed yet — there
     * is nothing above to climb over — so the cursor ascends into whatever
     * was already on your terminal and repaints it. Compose guards this with
     * a one-shot latch (its `repeated` flag, tty.go:303-307); expressed
     * naturally, the guard is just "move up by the previous count, which
     * starts at zero". Run with --latch to see the fix.
     *
     * @param lines Height of the painted block, and how much scrollback the
     *              broken version destroys
     * @param latch Move up by the previous frame's count (zero on the first
     *              frame) instead of the current frame's — the fix
     */
    @Command
    public void eat(@Out final PrintStream out,
                    @Option("lines") @Default("4") final int lines,
                    @Option("latch") final boolean latch) throws InterruptedException {

        // Sacrificial scrollback, standing in for your shell history
        for (int i = 1; i <= lines; i++) {
            out.println("precious scrollback line " + i + " — please do not overwrite me");
        }
        out.flush();
        Thread.sleep(1500);

        int numLines = 0;
        for (int frame = 1; frame <= 30; frame++) {
            final int up = latch ? numLines : lines; // the bug: current vs previous count
            if (up > 0) {
                out.printf("\u001B[%dA", up); // ESC[nA: cursor up n rows, same column
            }
            out.print('\r'); // \r: cursor to column 0, same row
            for (int i = 1; i <= lines; i++) {
                out.println(Tasks.pad("block line " + i + "  (frame " + frame + ")", 60));
            }
            numLines = lines;
            out.flush();
            Thread.sleep(100);
        }
    }
}
