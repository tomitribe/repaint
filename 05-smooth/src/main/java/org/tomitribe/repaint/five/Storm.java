package org.tomitribe.repaint.five;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Storm {

    /**
     * Prove the two-rates split: millions of updates, dozens of frames.
     *
     * Each worker hammers its job's progress counter — and calls
     * renderer.update() — as fast as the CPU allows, tens of millions of
     * times in a few seconds. The painter ticks at --fps regardless. The
     * summary at the end is the chapter's whole argument in four numbers:
     * the model saw every update; the terminal saw only the latest state,
     * at most fps times a second; and ticks where nothing visible changed
     * cost zero bytes (watch frames-skipped climb if you raise --fps past
     * the 10 Hz the timer column changes at).
     *
     * @param jobCount Number of hammering workers
     * @param millions Units of work per job, in millions
     * @param fps Paint ticks per second
     */
    @Command
    public void storm(@Out final PrintStream out,
                      @Option("jobs") @Default("3") final int jobCount,
                      @Option("millions") @Default("20") final int millions,
                      @Option("fps") @Default("10") final int fps) throws InterruptedException {

        final Terminal terminal = Terminal.detect();
        final SmoothRenderer r = new SmoothRenderer(out, terminal.width(), fps);

        final List<Job> jobs = new ArrayList<>();
        for (int i = 1; i <= jobCount; i++) {
            final Job job = new Job("hammer-" + i);
            job.expect(millions * 1_000_000L);
            jobs.add(job);
        }

        final List<Thread> workers = new ArrayList<>();
        for (final Job job : jobs) {
            final Thread worker = new Thread(() -> {
                while (!job.done()) {
                    job.advance();
                    r.update(jobs); // message rate: as fast as the CPU allows
                }
            });
            worker.start();
            workers.add(worker);
        }
        for (final Thread worker : workers) {
            worker.join();
        }
        r.close();

        final long skipped = r.ticks() - r.painted();
        out.println();
        out.printf("model updates:     %,d%n", r.updates());
        out.printf("paint ticks:       %,d%n", r.ticks());
        out.printf("frames painted:    %,d%n", r.painted());
        out.printf("frames skipped:    %,d (built, compared, unchanged — zero bytes)%n", skipped);
        if (r.painted() > 0) {
            out.printf("updates per frame: %,d%n", r.updates() / r.painted());
        }
    }
}
