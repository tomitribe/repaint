package org.tomitribe.repaint.one;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

/**
 * Chapter 01 — one line, rewritten in place.
 *
 * The entire mechanism of this chapter is two facts:
 *
 *   1. '\r' (carriage return) moves the cursor to column 0 of the CURRENT
 *      line without starting a new one. Whatever is printed next overwrites
 *      what was there.
 *   2. PrintStream only flushes on '\n'. A line that is never "finished"
 *      with a newline must be flushed by hand or nothing appears at all.
 */
public class OneLine {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * What everyone writes first: a new line per update. Run it once to feel
     * the problem every other module in this repo exists to solve.
     * @param steps TODO
     * @param intervalMs TODO
     */
    @Command
    public void naive(@Out final PrintStream out,
                      @Option("steps") @Default("50") final int steps,
                      @Option("interval-ms") @Default("100") final long intervalMs) throws InterruptedException {
        for (int i = 0; i <= steps; i++) {
            out.println("working... " + (i * 100 / steps) + "%");
            Thread.sleep(intervalMs);
        }
    }

    /**
     * The same progress, on one line. '\r' + overwrite; no newline until done.
     *
     * @param width TODO
     * @param durationSeconds TODO
     */
    @Command
    public void progress(@Out final PrintStream out,
                         @Option("width") @Default("40") final int width,
                         @Option("duration") @Default("5") final int durationSeconds) throws InterruptedException {
        final int steps = 100;
        final long intervalMs = durationSeconds * 1000L / steps;
        for (int pct = 0; pct <= steps; pct++) {
            final int filled = width * pct / 100;
            final StringBuilder line = new StringBuilder("\r[");
            for (int i = 0; i < width; i++) {
                line.append(i < filled ? '=' : (i == filled ? '>' : ' '));
            }
            line.append("] ").append(pct).append('%');
            out.print(line);
            out.flush();
            Thread.sleep(intervalMs);
        }
        out.println();
    }

    /**
     * A spinner with an elapsed timer — the line changes even though no
     * "progress" is being made. This is why animation needs a tick of its
     * own rather than waiting on events.
     */
    @Command
    public void spinner(@Out final PrintStream out,
                        @Option("message") @Default("thinking") final String message,
                        @Option("duration") @Default("10") final int durationSeconds,
                        @Option("interval-ms") @Default("100") final long intervalMs) throws InterruptedException {
        final long start = System.nanoTime();
        final long end = start + durationSeconds * 1_000_000_000L;
        int frame = 0;
        while (System.nanoTime() < end) {
            final double elapsed = (System.nanoTime() - start) / 1_000_000_000d;
            out.printf("\r%s %s %.1fs", FRAMES[frame], message, elapsed);
            out.flush();
            frame = (frame + 1) % FRAMES.length;
            Thread.sleep(intervalMs);
        }
        out.printf("\r✔ %s %.1fs%n", message, durationSeconds * 1.0);
    }

    /**
     * The chapter's own defect, on purpose: a countdown SHRINKS ("10.0s" is
     * one character wider than "9.9s"), and '\r' + overwrite never erases —
     * the orphaned character from the longer frame stays on screen.
     *
     * Run without --pad to see it; with --pad the line is right-padded to a
     * fixed width so the leftover is overwritten with spaces. Overwrite-with-
     * padding (not erase-then-write) is the fix every surveyed renderer uses.
     *
     * @param fromSeconds TODO
     * @param pad TODO
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
