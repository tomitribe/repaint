package org.tomitribe.repaint.six.jline;

import java.util.concurrent.ThreadLocalRandom;

/** The model that crosses the seam — status only; decoration is the renderer's. */
public final class Job {

    private final String name;
    private final long started = System.nanoTime();
    private volatile long ended;
    private volatile boolean done;

    public Job(final String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public boolean done() {
        return done;
    }

    public String status() {
        return done ? "Done" : "Working";
    }

    public double elapsedSeconds() {
        final long now = done ? ended : System.nanoTime();
        return (now - started) / 1_000_000_000d;
    }

    public long startedNanos() {
        return started;
    }

    /** Demo scaffolding: pretend to work for a random number of seconds. */
    public void work(final int minSeconds, final int maxSeconds) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minSeconds * 1000L, maxSeconds * 1000L + 1));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ended = System.nanoTime();
        done = true;
    }
}
