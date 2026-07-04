package org.tomitribe.repaint.five;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The whole anti-flicker checklist in one class.
 *
 * update() never touches the terminal — it stores the latest model and
 * returns, so producers can call it at any rate. A painter thread ticks at
 * fps and does all the work: build the frame as pure strings, skip it if
 * identical to the last one painted, otherwise assemble every byte —
 * synchronized-update begin, climb, lines, synchronized-update end — into
 * ONE buffer and write it with ONE call. The cursor is hidden once for the
 * renderer's LIFETIME (first frame) and shown at close — not per frame,
 * which leaves a parked, blinking cursor below the block between frames.
 *
 * The counters exist for the `storm` command: they are how the chapter
 * proves that the model saw millions of updates while the terminal saw
 * dozens of frames.
 */
public class SmoothRenderer implements Renderer {

    static final String HIDE = "\u001B[?25l"; // ESC[?25l: hide cursor
    static final String SHOW = "\u001B[?25h"; // ESC[?25h: show cursor

    private final PrintStream out;
    private final int width;
    private final Thread painter;

    private volatile List<Job> latest;
    private volatile boolean running = true;

    private final AtomicLong updates = new AtomicLong();
    private long ticks;
    private long painted;
    private List<String> lastLines = List.of();
    private int numLines; // physical lines printed by the previous frame

    public SmoothRenderer(final PrintStream out, final int width, final int fps) {
        this.out = out;
        this.width = width;
        this.painter = new Thread(() -> {
            while (running) {
                paint();
                try {
                    Thread.sleep(1000L / fps);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "repaint-painter");
        this.painter.setDaemon(true);
        this.painter.start();
        // The cursor is hidden for the renderer's whole lifetime (first
        // paint) and shown again in close() — hiding per frame leaves a
        // parked, blinking cursor below the block between frames (compose
        // has exactly that artifact; bubbletea hides for the lifetime).
        // Lifetime-hide raises the restore stakes: this hook puts the
        // cursor back even on Ctrl-C. kill -9 still leaks — if your cursor
        // ever goes missing, run:  printf '\x1b[?25h'
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            out.print(SHOW);
            out.flush();
        }));
    }

    /** Store the latest model and return. Called at message rate; costs two stores. */
    @Override
    public void update(final List<Job> jobs) {
        latest = jobs;
        updates.incrementAndGet();
    }

    /** One tick: build, compare, and — only if something changed — write once. */
    private synchronized void paint() {
        final List<Job> jobs = latest;
        if (jobs == null) {
            return;
        }
        ticks++;

        final List<String> lines = Decor.lines(jobs);
        if (lines.equals(lastLines)) {
            return; // dirty check: an unchanged frame costs no bytes at all
        }

        final String prefix = painted == 0 ? HIDE : ""; // hide once, on the first frame, in the same write
        out.print(prefix + frame(lines, numLines, width)); // the whole frame is ONE write...
        out.flush();                                       // ...and one flush
        numLines = Math.max(lines.size(), numLines);
        lastLines = lines;
        painted++;
    }

    /**
     * Assemble a complete frame as one string — pure, and therefore
     * golden-testable down to the byte (see FrameTest).
     */
    static String frame(final List<String> lines, final int prevNumLines, final int width) {
        final StringBuilder frame = new StringBuilder();
        frame.append("\u001B[?2026h"); // ESC[?2026h: begin synchronized update — buffer until the end marker
        if (prevNumLines > 0) {
            frame.append("\u001B[").append(prevNumLines).append('A'); // ESC[nA: cursor up n rows, same column
        }
        frame.append('\r'); // \r: cursor to column 0, same row
        int printed = 0;
        for (final String line : lines) {
            frame.append(Width.fit(line, width)).append('\n');
            printed++;
        }
        for (int i = printed; i < prevNumLines; i++) {
            frame.append(" ".repeat(width)).append('\n'); // wipe rows the previous frame used and this one doesn't
        }
        frame.append("\u001B[?2026l"); // ESC[?2026l: end synchronized update — render atomically
        return frame.toString();
    }

    /**
     * Clean shutdown is part of the frame protocol. The interrupt only
     * shortens the painter's SLEEP — Throttle guarantees an in-flight
     * frame's bytes are never truncated, so the bookkeeping ("I painted X")
     * is always truthful and the final paint can use the honest dirty
     * check: it emits the finished state if the ticker hasn't already,
     * and zero bytes if it has. (An earlier revision forced this paint
     * past the dirty check — compensation for a truncation bug fixed at
     * the source, and a gratuitous full-block flash at exit on terminals
     * without 2026.)
     */
    @Override
    public void close() {
        running = false;
        painter.interrupt();
        try {
            painter.join(2000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        paint(); // final frame: the finished state — or zero bytes if already shown
        out.print(SHOW); // lifetime-hide ends here
        out.flush();
    }

    public long updates() {
        return updates.get();
    }

    public long ticks() {
        return ticks;
    }

    public long painted() {
        return painted;
    }
}
