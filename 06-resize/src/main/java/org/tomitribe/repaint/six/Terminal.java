package org.tomitribe.repaint.six;

/**
 * Chapter 03's Terminal with the exec guts swapped for syscalls — the
 * shape barely changed, which was the point of keeping it a plain class.
 * The differences that matter:
 *
 *   - size() is CHEAP now (one ioctl, no forked processes), so callers
 *     may re-ask freely instead of treating size as a startup snapshot
 *   - it measures fd 1, the exact descriptor we draw on
 *   - onResize() turns SIGWINCH into a callback: resize is a push
 */
public final class Terminal {

    public static final int STDOUT = 1;

    public boolean tty() {
        return Libc.isatty(STDOUT);
    }

    /** Current size, measured this instant; 80x24 when not a terminal. */
    public Size size() {
        final int[] rowsCols = Libc.winsize(STDOUT);
        if (rowsCols == null) {
            return new Size(80, 24);
        }
        return new Size(rowsCols[1], rowsCols[0]);
    }

    /** The callback fires on a daemon thread each time the window changes size. */
    public void onResize(final Runnable callback) {
        Libc.onWinch(callback);
    }
}
