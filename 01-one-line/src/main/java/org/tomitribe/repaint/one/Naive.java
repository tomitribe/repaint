package org.tomitribe.repaint.one;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Naive {

    /**
     * What everyone writes first: a new line per update. Run it once to feel
     * the problem every other module in this repo exists to solve.
     *
     * @param steps Number of updates to print, each on its own line
     * @param intervalMs Milliseconds to sleep between updates
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
}
