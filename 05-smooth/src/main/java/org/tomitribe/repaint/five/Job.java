package org.tomitribe.repaint.five;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The model that crosses the seam — chapter 03's Job, grown a progress
 * counter so a producer can hammer it at message rate. Still no decoration:
 * spinners, bars, and timers are computed by renderers at paint time.
 */
public final class Job {

    private final String name;
    private final long started = System.nanoTime();
    private volatile long ended;
    private volatile boolean done;
    private volatile long current;
    private volatile long total; // 0 = no progress bar

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

    public long current() {
        return current;
    }

    public long total() {
        return total;
    }

    /** Declare how much work this job expects; enables the progress bar. */
    public void expect(final long total) {
        this.total = total;
    }

    /** One unit of work done. The producer may call this millions of times a second. */
    public void advance() {
        final long now = ++current;
        if (total > 0 && now >= total) {
            ended = System.nanoTime();
            done = true;
        }
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
