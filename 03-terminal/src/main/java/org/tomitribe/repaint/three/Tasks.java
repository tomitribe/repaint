package org.tomitribe.repaint.three;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Tasks {

    /** How to choose the renderer: probe the stream, or force one side. */
    public enum Mode { auto, block, plain }

    /**
     * Chapter 02's task list, now behind the seam: probe first, then paint.
     *
     * Run it bare and you get the block renderer at your terminal's real,
     * measured width — try it in a narrow window where chapter 02 grew
     * stairs. Pipe it (`tasks | cat`) and the same producer drives the plain
     * renderer instead: one line per status change, no escape codes, a
     * readable log. The --renderer option is the escape hatch every surveyed
     * tool also ships (docker's --progress=tty|plain) for when the probe
     * guesses wrong.
     *
     * @param count Number of parallel tasks to run
     * @param fps Paint ticks per second
     * @param renderer auto probes stdout; block or plain forces that renderer
     * @param minSeconds Fastest a task may finish
     * @param maxSeconds Slowest a task may finish
     */
    @Command
    public void tasks(@Out final PrintStream out,
                      @Option("tasks") @Default("6") final int count,
                      @Option("fps") @Default("10") final int fps,
                      @Option("renderer") @Default("auto") final Mode renderer,
                      @Option("min-seconds") @Default("2") final int minSeconds,
                      @Option("max-seconds") @Default("8") final int maxSeconds) throws InterruptedException {

        final Terminal terminal = Terminal.detect();
        final Renderer r = switch (renderer) {
            case auto -> Renderer.of(out, terminal);
            case block -> new BlockRenderer(out, terminal.width());
            case plain -> new PlainRenderer(out);
        };

        final List<Job> jobs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            jobs.add(new Job("task-" + i));
        }

        // Producers: mutate the model, never the terminal
        final List<Thread> workers = new ArrayList<>();
        for (final Job job : jobs) {
            final Thread worker = new Thread(() -> job.work(minSeconds, maxSeconds));
            worker.start();
            workers.add(worker);
        }

        // The painter: hands the model to whichever renderer the probe chose
        boolean finalFrame = false;
        while (!finalFrame) {
            finalFrame = jobs.stream().allMatch(Job::done);
            r.update(jobs);
            Thread.sleep(1000L / fps);
        }
        r.close();

        for (final Thread worker : workers) {
            worker.join();
        }
    }
}
