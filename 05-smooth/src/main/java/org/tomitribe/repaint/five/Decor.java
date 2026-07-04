package org.tomitribe.repaint.five;

import java.util.ArrayList;
import java.util.List;

/**
 * Frame content, computed as pure strings — shared by the naive and smooth
 * renderers so their only difference is HOW they write, never WHAT.
 */
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
        final String bar = job.total() > 0 ? " " + bar(job, 20) : "";
        return " " + spin + " " + Width.fit(job.name(), 14)
                + bar
                + Width.fit(job.status(), 9)
                + String.format("%5.1fs", job.elapsedSeconds());
    }

    private static String bar(final Job job, final int cells) {
        final int filled = (int) (cells * Math.min(job.current(), job.total()) / job.total());
        final int pct = (int) (100 * Math.min(job.current(), job.total()) / job.total());
        return "[" + "=".repeat(filled) + " ".repeat(cells - filled) + "] " + String.format("%3d%% ", pct);
    }
}
