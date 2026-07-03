package org.tomitribe.repaint.two;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Shrink {

    /**
     * Chapter 01's countdown defect, gone vertical: orphaned lines.
     *
     * Jobs finish and their lines are removed, so this frame prints fewer
     * lines than the last one — and overwriting never erases, so the rows the
     * block no longer reaches just stay on screen. Run without --wipe to see
     * the orphans pile up below the shrinking block; with --wipe the painter
     * prints full-width blank lines over them and counts those blanks in its
     * line accounting, exactly as compose does (tty.go:380-384).
     *
     * @param from How many jobs the block starts with; one finishes per second
     * @param wipe Blank out rows the previous frame used and this one doesn't
     * @param width Line width to pad to
     */
    @Command
    public void shrink(@Out final PrintStream out,
                       @Option("from") @Default("6") final int from,
                       @Option("wipe") final boolean wipe,
                       @Option("width") @Default("60") final int width) throws InterruptedException {

        final long started = System.nanoTime();
        int numLines = 0;
        while (true) {
            final double elapsed = (System.nanoTime() - started) / 1_000_000_000d;
            final int remaining = Math.max(from - (int) elapsed, 0);

            final List<String> lines = new ArrayList<>();
            lines.add("[+] jobs remaining: " + remaining);
            for (int i = 1; i <= remaining; i++) {
                lines.add(" ⣶ job-" + i + "  running  " + String.format("%.1fs", elapsed));
            }

            if (numLines > 0) {
                out.printf("\u001B[%dA", numLines); // ESC[nA: cursor up n rows, same column
            }
            out.print('\r'); // \r: cursor to column 0, same row
            int printed = 0;
            for (final String line : lines) {
                out.println(Tasks.pad(line, width));
                printed++;
            }
            if (wipe) {
                // Wiped rows count as printed: the next frame must climb
                // over them too, or the accounting drifts
                for (int i = printed; i < numLines; i++) {
                    out.println(" ".repeat(width));
                    printed++;
                }
            }
            numLines = printed;
            out.flush();

            if (remaining == 0) {
                return;
            }
            Thread.sleep(100);
        }
    }
}
