package org.tomitribe.repaint.six;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Chapter 05's renderer with the two size-related fixes:
 *
 * HEIGHT CLAMP — the block never paints more rows than the window has,
 * collapsing the excess into "… N more". A block taller than the window
 * doesn't just look wrong: the climb clamps at the screen top and every
 * frame pumps sheared fragments into scrollback (field-found; compose's
 * defense, tty.go:325). The invariant: after its first frame, a
 * well-behaved block never scrolls the screen.
 *
 * LIVE RESIZE — SIGWINCH sets a flag; the next paint re-measures, climbs
 * the old block, erases below (ESC[J), and repaints fresh at the new
 * size. Best effort on shrink: terminals rewrap old rows during resize,
 * so the climb can land off by the rewrapped lines — the same limitation
 * compose accepts.
 */
public class SmoothRenderer implements Renderer {

    static final String HIDE = "\u001B[?25l"; // ESC[?25l: hide cursor
    static final String SHOW = "\u001B[?25h"; // ESC[?25h: show cursor

    private final PrintStream out;
    private final Terminal terminal;
    private final Thread painter;

    private volatile List<Job> latest;
    private volatile boolean running = true;
    private volatile boolean resized;

    private Size size;
    private long painted;
    private List<String> lastLines = List.of();
    private int numLines; // physical lines printed by the previous frame

    public SmoothRenderer(final PrintStream out, final Terminal terminal, final int fps) {
        this.out = out;
        this.terminal = terminal;
        this.size = terminal.size();
        terminal.onResize(() -> resized = true); // push, not poll
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            out.print(SHOW); // restore on Ctrl-C; kill -9 has no cure: printf '\x1b[?25h'
            out.flush();
        }));
    }

    @Override
    public void update(final List<Job> jobs) {
        latest = jobs;
    }

    private synchronized void paint() {
        final List<Job> jobs = latest;
        if (jobs == null) {
            return;
        }

        String reset = "";
        if (resized) {
            resized = false;
            size = terminal.size(); // one cheap ioctl — ask again, don't cache stale
            final StringBuilder sb = new StringBuilder();
            if (numLines > 0) {
                sb.append("\u001B[").append(numLines).append('A'); // ESC[nA: cursor up n rows, same column
            }
            sb.append('\r'); // \r: cursor to column 0, same row
            sb.append("\u001B[J"); // ESC[J: erase from cursor to end of screen
            reset = sb.toString();
            numLines = 0; // start fresh at the new dimensions
            lastLines = List.of();
        }

        final List<String> lines = clamp(Decor.lines(jobs), size.height());
        if (reset.isEmpty() && lines.equals(lastLines)) {
            return; // dirty check: an unchanged frame costs no bytes at all
        }

        final String prefix = painted == 0 ? HIDE : ""; // lifetime hide, first frame
        out.print(prefix + reset + frame(lines, numLines, size.width()));
        out.flush();
        numLines = Math.max(lines.size(), numLines);
        lastLines = lines;
        painted++;
    }

    /**
     * Never paint more rows than the window has: keep the first
     * (height - 2) lines and collapse the rest into "… N more". The block
     * plus the cursor's resting row below it then exactly fit the window,
     * so the block can never be the thing that scrolls the screen.
     */
    static List<String> clamp(final List<String> lines, final int height) {
        final int max = Math.max(height - 1, 2); // block rows + 1 resting row = height
        if (lines.size() <= max) {
            return lines;
        }
        final List<String> clamped = new ArrayList<>(lines.subList(0, max - 1));
        clamped.add("… " + (lines.size() - (max - 1)) + " more");
        return clamped;
    }

    /** Assemble a complete frame as one string — pure, golden-testable (FrameTest). */
    static String frame(final List<String> lines, final int prevNumLines, final int width) {
        final StringBuilder frame = new StringBuilder();
        frame.append("\u001B[?2026h"); // ESC[?2026h: begin synchronized update
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

    @Override
    public void close() {
        running = false;
        painter.interrupt();
        try {
            painter.join(2000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        paint(); // final frame — or zero bytes if the ticker already showed it
        out.print(SHOW); // lifetime-hide ends here
        out.flush();
    }
}
