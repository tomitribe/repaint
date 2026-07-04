package org.tomitribe.repaint.five;

import java.io.PrintStream;
import java.util.List;

/**
 * Chapter 04's painter, kept as this chapter's exhibit: paints on EVERY
 * update() call, one unbuffered write per line, cursor visible throughout,
 * repaints even when nothing changed. Run `tasks --naive --fps 60` and
 * watch for the cursor racing and the occasional half-painted frame —
 * then drop the flag.
 */
public class BlockRenderer implements Renderer {

    private final PrintStream out;
    private final int width;
    private int numLines; // physical lines printed by the previous frame

    public BlockRenderer(final PrintStream out, final int width) {
        this.out = out;
        this.width = width;
    }

    @Override
    public void update(final List<Job> jobs) {
        final List<String> lines = Decor.lines(jobs);
        if (numLines > 0) {
            out.printf("\u001B[%dA", numLines); // ESC[nA: cursor up n rows, same column
        }
        out.print('\r'); // \r: cursor to column 0, same row
        int printed = 0;
        for (final String line : lines) {
            out.println(Width.fit(line, width));
            printed++;
        }
        for (int i = printed; i < numLines; i++) {
            out.println(" ".repeat(width)); // wipe rows the previous frame used and this one doesn't
            printed++;
        }
        numLines = printed;
        out.flush();
    }

    @Override
    public void close() {
        out.flush();
    }
}
