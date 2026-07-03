package org.tomitribe.repaint.one;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Progress {

    /**
     * The same progress, on one line. '\r' + overwrite; no newline until done.
     *
     * @param width Width of the bar in characters, brackets not included
     * @param durationSeconds Seconds the bar takes to reach 100%
     */
    @Command
    public void progress(@Out final PrintStream out,
                         @Option("width") @Default("40") final int width,
                         @Option("duration") @Default("5") final int durationSeconds) throws InterruptedException {
        final int steps = 100;
        final long intervalMs = durationSeconds * 1000L / steps;
        for (int pct = 0; pct <= steps; pct++) {
            final int filled = width * pct / 100;
            final StringBuilder line = new StringBuilder("\r["); // \r: cursor to column 0, same row
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
}
