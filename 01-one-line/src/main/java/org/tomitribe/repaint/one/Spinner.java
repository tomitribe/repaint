package org.tomitribe.repaint.one;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * A spinner with an elapsed timer — the line changes even though no
     * "progress" is being made. This is why animation needs a tick of its
     * own rather than waiting on events.
     *
     * @param message Text to display beside the spinner
     * @param durationSeconds Seconds to spin before declaring victory
     * @param intervalMs Milliseconds between frames; the spinner's speed
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
            out.printf("\r%s %s %.1fs", FRAMES[frame], message, elapsed); // \r: cursor to column 0, same row
            out.flush();
            frame = (frame + 1) % FRAMES.length;
            Thread.sleep(intervalMs);
        }
        out.printf("\r✔ %s %.1fs%n", message, durationSeconds * 1.0);
    }
}
