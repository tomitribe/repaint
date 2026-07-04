package org.tomitribe.repaint.four;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Tasks {

    private static final String[] UNICODE_NAMES = {
            "コンパイル", "デプロイ", "🚀-launch", "café-sync", "테스트", "check",
    };

    /**
     * Chapter 03's task list with names the real world actually uses.
     *
     * The default names are CJK, Hangul, emoji, and accented — and with
     * --fit=cells every column lines up, because all padding and alignment
     * runs through one cell-aware width function. Run --fit=chars to get
     * chapter 03's arithmetic back: the status and timer columns stagger,
     * exactly the latent bug docker ships in `compose up`
     * (docs/docker-live-terminal-rendering.md §7). --ascii retreats to the
     * names where both arithmetics happen to agree.
     *
     * @param count Number of parallel tasks to run
     * @param fps Paint ticks per second
     * @param fit cells measures display width; chars is chapter 03's bug
     * @param ascii Use plain ASCII task names, where chars == cells
     * @param minSeconds Fastest a task may finish
     * @param maxSeconds Slowest a task may finish
     */
    @Command
    public void tasks(@Out final PrintStream out,
                      @Option("tasks") @Default("6") final int count,
                      @Option("fps") @Default("10") final int fps,
                      @Option("fit") @Default("cells") final BlockRenderer.Fit fit,
                      @Option("ascii") final boolean ascii,
                      @Option("min-seconds") @Default("2") final int minSeconds,
                      @Option("max-seconds") @Default("8") final int maxSeconds) throws InterruptedException {

        final Terminal terminal = Terminal.detect();
        final Renderer r = terminal.tty()
                ? new BlockRenderer(out, terminal.width(), fit)
                : new PlainRenderer(out);

        final List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final String name = ascii ? "task-" + (i + 1) : UNICODE_NAMES[i % UNICODE_NAMES.length];
            jobs.add(new Job(name));
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
            Thread.sleep(1000L / fps);
        }
        r.close();

        for (final Thread worker : workers) {
            worker.join();
        }
    }
}
