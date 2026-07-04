package org.tomitribe.repaint.four;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The non-TTY downgrade: append-only, no cursor motion, no escape codes,
 * no decoration. A line is printed only when a job's STATUS changes, so a
 * pipe or CI log reads as a sequence of events — start, done, done, done —
 * no matter how fast the producer ticks. This is why the model crossing
 * the seam carries no spinner or ticking timer: anything that changes
 * every frame would flood an append-only stream.
 *
 * Compare `docker compose up | cat`: same idea, same shape of output.
 */
public class PlainRenderer implements Renderer {

    private final PrintStream out;
    private final Map<String, String> reported = new HashMap<>();

    public PlainRenderer(final PrintStream out) {
        this.out = out;
    }

    @Override
    public void update(final List<Job> jobs) {
        for (final Job job : jobs) {
            final String status = job.status();
            if (status.equals(reported.get(job.name()))) {
                continue;
            }
            reported.put(job.name(), status);
            if (job.done()) {
                out.printf("%s  %s  %.1fs%n", job.name(), status, job.elapsedSeconds());
            } else {
                out.printf("%s  %s%n", job.name(), status);
            }
        }
        out.flush();
    }

    @Override
    public void close() {
        out.flush();
    }
}
