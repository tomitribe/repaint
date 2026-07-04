package org.tomitribe.repaint.four;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Chapter 03's block painter with the arithmetic made honest: every pad,
 * truncate, and column alignment goes through {@link Width}. The old
 * char-counting versions are kept, selectable via {@link Fit#chars}, so
 * the docker bug can be reproduced on demand — run `tasks --fit=chars`
 * and watch the timer column stagger.
 */
public class BlockRenderer implements Renderer {

    /** cells measures display width correctly; chars is chapter 03's bug, kept on purpose. */
    public enum Fit { cells, chars }

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PrintStream out;
    private final int width;
    private final Fit fit;
    private int numLines; // physical lines printed by the previous frame

    public BlockRenderer(final PrintStream out, final int width) {
        this(out, width, Fit.cells);
    }

    public BlockRenderer(final PrintStream out, final int width, final Fit fit) {
        this.out = out;
        this.width = width;
        this.fit = fit;
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
            out.println(fitLine(line));
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

    private String decorate(final Job job) {
        final String spin = job.done()
                ? "✔"
                : FRAMES[(int) ((System.nanoTime() - job.startedNanos()) / 100_000_000L % FRAMES.length)];
        // column alignment is also width arithmetic: pad the name to 14
        // CELLS, not 14 chars — "デプロイ" is 4 chars but 8 cells
        return " " + spin + " " + column(job.name(), 14)
                + column(job.status(), 9)
                + String.format("%5.1fs", job.elapsedSeconds());
    }

    private String column(final String s, final int cells) {
        return fit == Fit.cells
                ? Width.fit(s, cells)
                : chapterThreeFit(s, cells);
    }

    private String fitLine(final String line) {
        return fit == Fit.cells
                ? Width.fit(line, width)
                : chapterThreeFit(line, width);
    }

    /** Chapter 03's arithmetic, preserved as the exhibit: chars, not cells. */
    static String chapterThreeFit(final String line, final int width) {
        if (line.length() > width) {
            return line.substring(0, width); // can split a surrogate pair in half
        }
        return line + " ".repeat(width - line.length());
    }
}
