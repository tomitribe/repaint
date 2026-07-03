package org.tomitribe.repaint.two;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Tasks {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * A live task list — one line per parallel task, all updating at once.
     *
     * This is the docker-compose model end to end: worker threads only mutate
     * the task model and NEVER touch the terminal; a single painter loop owns
     * the screen, repainting the whole block every tick — cursor-up over the
     * previous frame, rewrite every line padded to full width, and the
     * newlines walk the cursor back down. Try --fps 60 and watch closely:
     * the flicker you may see is this chapter's open defect, fixed in
     * chapter 05.
     *
     * @param count Number of parallel tasks to run
     * @param fps Paint ticks per second (compose uses 10)
     * @param width Line width to pad/truncate to — a guess until chapter 03
     * @param minSeconds Fastest a task may finish
     * @param maxSeconds Slowest a task may finish
     */
    @Command
    public void tasks(@Out final PrintStream out,
                      @Option("tasks") @Default("6") final int count,
                      @Option("fps") @Default("10") final int fps,
                      @Option("width") @Default("80") final int width,
                      @Option("min-seconds") @Default("2") final int minSeconds,
                      @Option("max-seconds") @Default("8") final int maxSeconds) throws InterruptedException {

        final List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(new Task("task-" + i));
        }

        // Producers: mutate the model, never the terminal
        final List<Thread> workers = new ArrayList<>();
        for (final Task task : tasks) {
            final Thread worker = new Thread(() -> task.work(minSeconds, maxSeconds));
            worker.start();
            workers.add(worker);
        }

        // The painter: sole owner of the terminal
        int numLines = 0; // physical lines printed by the PREVIOUS frame
        boolean finalFrame = false;
        while (!finalFrame) {
            finalFrame = tasks.stream().allMatch(Task::done);

            final List<String> lines = new ArrayList<>();
            final long done = tasks.stream().filter(Task::done).count();
            lines.add("[+] tasks " + done + "/" + count);
            for (final Task task : tasks) {
                lines.add(task.line());
            }

            // First frame: numLines is 0 and we must emit NOTHING — ESC[0A
            // would move up one line and eat the shell prompt (see `eat`)
            if (numLines > 0) {
                out.printf("\u001B[%dA", numLines); // ESC[nA: cursor up n rows, same column
            }
            out.print('\r'); // \r: cursor to column 0, same row
            for (final String line : lines) {
                out.println(pad(line, width));
            }
            numLines = lines.size();
            out.flush();

            Thread.sleep(1000L / fps);
        }

        for (final Thread worker : workers) {
            worker.join();
        }
    }

    static String pad(final String line, final int width) {
        // char-based fit: knowingly wrong for wide glyphs — chapter 04's subject
        if (line.length() > width) {
            return line.substring(0, width);
        }
        return line + " ".repeat(width - line.length());
    }

    static class Task {
        private final String name;
        private final long started = System.nanoTime();
        private volatile long ended;
        private volatile boolean done;

        Task(final String name) {
            this.name = name;
        }

        void work(final int minSeconds, final int maxSeconds) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(minSeconds * 1000L, maxSeconds * 1000L));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ended = System.nanoTime();
            done = true;
        }

        boolean done() {
            return done;
        }

        String line() {
            final long now = done ? ended : System.nanoTime();
            final double elapsed = (now - started) / 1_000_000_000d;
            // Spinner frame derives from wall-clock, not from the paint tick —
            // fps changes must not change how fast the spinner spins. Compose
            // gets this wrong (its spinner clock is never reset; see
            // docs/SURVEY.md, "Decouple spinner timing from frame rate")
            final String spin = done ? "✔" : FRAMES[(int) ((now - started) / 100_000_000L % FRAMES.length)];
            final String status = done ? "Done" : "Working";
            return String.format(" %s %-8s %-8s %5.1fs", spin, name, status, elapsed);
        }
    }
}
