package org.tomitribe.repaint.five;

import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * What we know about the terminal we're attached to — if we're attached to
 * one at all. Detection happens once, at startup; reacting to live resize
 * is chapter 06's subject.
 *
 * Java 17 has no isatty() and no TIOCGWINSZ, so this implementation shells
 * out — the same last-resort path JLine keeps for JVMs without native access
 * (its ExecPty runs stty/tty; see docs/jline3-live-terminal-rendering.md §12).
 * Chapter 06 replaces the internals with FFM downcalls; this type's shape is
 * built so nothing else has to change when it does.
 */
public final class Terminal {

    private final boolean tty;
    private final int width;
    private final int height;
    private final String sizeSource;

    private Terminal(final boolean tty, final int width, final int height, final String sizeSource) {
        this.tty = tty;
        this.width = width;
        this.height = height;
        this.sizeSource = sizeSource;
    }

    public boolean tty() {
        return tty;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String sizeSource() {
        return sizeSource;
    }

    /**
     * Probe stdout for terminal-ness and size.
     *
     * Scar to respect (docs/SURVEY.md, "Measure size on the same fd you draw
     * on"): docker-compose paints stderr but sizes stdout, so `compose up
     * >file` collapses to 80x24 on a wide terminal. We paint stdout, we probe
     * stdout — but note our size fallback chain isn't fd-perfect either:
     * `stty size` measures the terminal on the CHILD's stdin (inherited from
     * ours), which is usually the same terminal as stdout, but not always.
     * The honest fix is ioctl(TIOCGWINSZ) on the exact fd — chapter 06.
     */
    public static Terminal detect() {
        final boolean tty = isatty();
        if (!tty) {
            return new Terminal(false, 80, 24, "not a tty; default 80x24");
        }
        final int[] size = sttySize();
        if (size != null) {
            return new Terminal(true, size[1], size[0], "stty size");
        }
        final Integer cols = envInt("COLUMNS");
        final Integer rows = envInt("LINES");
        if (cols != null && rows != null) {
            return new Terminal(true, cols, rows, "COLUMNS/LINES env");
        }
        return new Terminal(true, 80, 24, "default 80x24");
    }

    /**
     * `test -t 1` asks whether file descriptor 1 (stdout) is a terminal —
     * the child INHERITS our stdout, so it is asking about ours. Exit code 0
     * means yes. This is the exec stand-in for the C isatty(1) call.
     */
    private static boolean isatty() {
        try {
            final Process p = new ProcessBuilder("sh", "-c", "test -t 1")
                    .redirectOutput(Redirect.INHERIT)
                    .start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * `stty size` prints "rows cols" for the terminal on ITS stdin — which we
     * arrange to be ours via inherit. Returns {rows, cols}, or null if stdin
     * isn't a terminal (e.g. `terminal tasks < /dev/null`) or stty is missing.
     */
    private static int[] sttySize() {
        try {
            final Process p = new ProcessBuilder("stty", "size")
                    .redirectInput(Redirect.INHERIT)
                    .redirectError(Redirect.DISCARD)
                    .start();
            final String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            final String[] parts = output.split("\\s+");
            if (parts.length != 2) {
                return null;
            }
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (final Exception e) {
            return null;
        }
    }

    private static Integer envInt(final String name) {
        final String value = System.getenv(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
