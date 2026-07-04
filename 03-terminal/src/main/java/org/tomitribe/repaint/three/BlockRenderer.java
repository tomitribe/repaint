package org.tomitribe.repaint.three;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Chapter 02's painter, promoted to a class — with two upgrades. The width
 * is no longer a guess: every line is truncated and padded to the MEASURED
 * terminal width, so the wrap staircase can't happen. And the decoration
 * moved in here from the producer: spinner frames and elapsed timers are
 * computed at paint time, because only a renderer that repaints can afford
 * content that changes every frame.
 */
public class BlockRenderer implements Renderer {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PrintStream out;
    private final int width;
    private int numLines; // physical lines printed by the previous frame

    public BlockRenderer(final PrintStream out, final int width) {
        this.out = out;
        this.width = width;
    }

    @Override
    public void update(final List<Job> jobs) {
        final List<String> lines = new ArrayList<>();
        final long done = jobs.stream().filter(Job::done).count();
        lines.add("[+] tasks " + done + "/" + jobs.size());
        for (final Job job : jobs) {
            lines.add(decorate(job));
        }

        if (numLines > 0) {
            out.printf("\u001B[%dA", numLines); // ESC[nA: cursor up n rows, same column
        }
        out.print('\r'); // \r: cursor to column 0, same row
        int printed = 0;
        for (final String line : lines) {
            out.println(fit(line, width));
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
        // cursor already rests below the block; nothing to restore — yet.
        // (chapter 05 hides the cursor during frames, and then this method
        // earns its keep)
        out.flush();
    }

    private String decorate(final Job job) {
        // spinner frame derives from wall-clock, not the paint tick, so fps
        // changes don't change spin speed (compose gets this wrong; see
        // docs/SURVEY.md, "Decouple spinner timing from frame rate")
        final String spin = job.done()
                ? "✔"
                : FRAMES[(int) ((System.nanoTime() - job.startedNanos()) / 100_000_000L % FRAMES.length)];
        return String.format(" %s %-8s %-8s %5.1fs", spin, job.name(), job.status(), job.elapsedSeconds());
    }

    static String fit(final String line, final int width) {
        // char-based fit: knowingly wrong for wide glyphs — chapter 04's subject
        if (line.length() > width) {
            return line.substring(0, width);
        }
        return line + " ".repeat(width - line.length());
    }
}
