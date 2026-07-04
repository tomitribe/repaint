package org.tomitribe.repaint.six;

import java.util.ArrayList;
import java.util.List;

/** Frame content as pure strings; all width arithmetic through Width. */
final class Decor {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private Decor() {
    }

    static List<String> lines(final List<Job> jobs) {
        final List<String> lines = new ArrayList<>();
        final long done = jobs.stream().filter(Job::done).count();
        lines.add("[+] tasks " + done + "/" + jobs.size());
        for (final Job job : jobs) {
            lines.add(decorate(job));
        }
        return lines;
    }

    static String decorate(final Job job) {
        final String spin = job.done()
                ? "✔"
                : FRAMES[(int) ((System.nanoTime() - job.startedNanos()) / 100_000_000L % FRAMES.length)];
        return " " + spin + " " + Width.fit(job.name(), 14)
                + Width.fit(job.status(), 9)
                + String.format("%5.1fs", job.elapsedSeconds());
    }
}
