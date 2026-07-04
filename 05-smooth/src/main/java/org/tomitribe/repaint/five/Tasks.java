package org.tomitribe.repaint.five;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Tasks {

    /**
     * The task list one last time — now without the flicker.
     *
     * The smooth renderer assembles each frame into a single buffered write,
     * hides the cursor for the duration, wraps the whole thing in
     * synchronized-update marks, and skips frames that wouldn't change a
     * pixel. Crank --fps 60 and compare against --naive (chapter 04's
     * painter: one write per line, cursor visible, paints whether or not
     * anything changed) — especially over ssh or in a busy terminal.
     *
     * @param count Number of parallel tasks to run
     * @param fps Paint ticks per second
     * @param naive Use chapter 04's per-line unbuffered painter instead
     * @param slow Simulate a laggy ssh link: bytes trickle to the terminal
     *             in small delayed chunks. Flicker is a transport
     *             phenomenon — this is the transport. Compare --naive
     *             --slow against plain --slow.
     * @param minSeconds Fastest a task may finish
     * @param maxSeconds Slowest a task may finish
     */
    @Command
    public void tasks(@Out final PrintStream out,
                      @Option("tasks") @Default("6") final int count,
                      @Option("fps") @Default("30") final int fps,
                      @Option("naive") final boolean naive,
                      @Option("slow") final boolean slow,
                      @Option("min-seconds") @Default("2") final int minSeconds,
                      @Option("max-seconds") @Default("8") final int maxSeconds) throws InterruptedException {

        final Terminal terminal = Terminal.detect();
        // the throttle wraps the stream; neither renderer knows it's there —
        // the same seam that swapped renderers in chapter 03 now swaps links
        final PrintStream sink = slow ? new PrintStream(new Throttle(out, 64, 2), false) : out;
        final Renderer r = !terminal.tty()
                ? new PlainRenderer(out)
                : naive ? new BlockRenderer(sink, terminal.width())
                        : new SmoothRenderer(sink, terminal.width(), fps);

        final List<Job> jobs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            jobs.add(new Job("task-" + i));
        }

        final List<Thread> workers = new ArrayList<>();
        for (final Job job : jobs) {
            final Thread worker = new Thread(() -> job.work(minSeconds, maxSeconds));
            worker.start();
            workers.add(worker);
        }

        // With the smooth renderer this loop is just the model's heartbeat —
        // painting happens on the renderer's own ticker at fps
        boolean finalFrame = false;
        while (!finalFrame) {
            finalFrame = jobs.stream().allMatch(Job::done);
            r.update(jobs);
            Thread.sleep(50);
        }
        r.close();

        for (final Thread worker : workers) {
            worker.join();
        }
    }
}
