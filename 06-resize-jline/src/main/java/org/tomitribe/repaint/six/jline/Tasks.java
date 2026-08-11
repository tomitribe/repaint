package org.tomitribe.repaint.six.jline;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Tasks {

    /**
     * Chapter 06's task list — clamp, live resize — rendered through JLine.
     *
     * Same demos as 06-resize: --tasks 50 in a small window collapses into
     * "… N more"; drag the corner mid-run and the block reflows. Watch it
     * side by side with 06's and try to spot a difference — then diff the
     * two modules and see how much code one of them didn't need.
     *
     * @param count Number of parallel tasks to run
     * @param fps Paint ticks per second
     * @param minSeconds Fastest a task may finish
     * @param maxSeconds Slowest a task may finish
     */
    @Command
    public void tasks(@Out final PrintStream out,
                      @Option("tasks") @Default("6") final int count,
                      @Option("fps") @Default("30") final int fps,
                      @Option("min-seconds") @Default("4") final int minSeconds,
                      @Option("max-seconds") @Default("15") final int maxSeconds)
            throws IOException, InterruptedException {

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            final Renderer r = !Terminal.TYPE_DUMB.equals(terminal.getType())
                    ? new SmoothRenderer(terminal, fps)
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
}
