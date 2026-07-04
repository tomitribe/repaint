package org.tomitribe.repaint.six;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Tasks {

    /**
     * The task list, now unbreakable by geometry.
     *
     * Run --tasks 50 in a small window: instead of shearing frames into
     * your scrollback (chapter 05's field-found failure), the block clamps
     * to the window and the excess collapses into "… N more". Then drag
     * the window corner mid-run: SIGWINCH fires, the block erases itself
     * and repaints at the new size — grow the window and rows emerge from
     * the "more" line; shrink it and they fold back in.
     *
     * @param count Number of parallel tasks to run
     * @param fps Paint ticks per second
     * @param minSeconds Fastest a task may finish
     * @param maxSeconds Slowest a task may finish
     */
    @Command(description = "The task list, unbreakable by geometry: height clamp + live resize")
    public void tasks(@Out final PrintStream out,
                      @Option("tasks") @Default("6") final int count,
                      @Option("fps") @Default("30") final int fps,
                      @Option("min-seconds") @Default("4") final int minSeconds,
                      @Option("max-seconds") @Default("15") final int maxSeconds) throws InterruptedException {

        final Terminal terminal = new Terminal();
        final Renderer r = terminal.tty()
                ? new SmoothRenderer(out, terminal, fps)
                : new PlainRenderer(out);

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
