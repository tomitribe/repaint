package org.tomitribe.repaint.six;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Probe {

    /**
     * Chapter 03's probe, re-asked with syscalls instead of forked shells.
     *
     * Same questions, better answers: isatty(1) is a direct libc call, the
     * size comes from ioctl(TIOCGWINSZ) on fd 1 — the exact descriptor we
     * draw on, no stdin/stdout mismatch possible — and it costs so little
     * you can ask per frame. Pipe it and watch the answers flip, same as
     * chapter 03.
     */
    @Command(description = "Chapter 03's questions, answered by syscalls instead of forked shells")
    public void probe(@Out final PrintStream out) {
        final Terminal terminal = new Terminal();
        out.println("java:    " + Runtime.version().feature() + " (FFM)");
        out.println("tty:     " + terminal.tty() + "  (libc isatty(1) — a downcall, not a forked shell)");
        final Size size = terminal.size();
        out.println("size:    " + size.width() + "x" + size.height()
                + "  (ioctl(1, TIOCGWINSZ) — the fd we draw on)");
        out.println("resize:  SIGWINCH push (sigaction + upcall stub)");
    }
}
