package org.tomitribe.repaint.six;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Watch {

    /**
     * Resize as a push: drag your window corner and watch the kernel talk.
     *
     * Installs a SIGWINCH handler and prints a line each time the window
     * changes size. Chapter 03 could only know the size at startup; now
     * the terminal tells US — no polling, no forked stty, just a signal,
     * one atomic store, and a dispatcher thread.
     *
     * @param seconds How long to watch before exiting
     */
    @Command(description = "Print a line each time the window resizes - SIGWINCH as a push")
    public void watch(@Out final PrintStream out,
                      @Option("seconds") @Default("30") final int seconds) throws InterruptedException {
        final Terminal terminal = new Terminal();
        final Size initial = terminal.size();
        out.println("now: " + initial.width() + "x" + initial.height()
                + " — drag the window corner (" + seconds + "s)...");
        terminal.onResize(() -> {
            final Size size = terminal.size();
            out.println("SIGWINCH → " + size.width() + "x" + size.height());
        });
        Thread.sleep(seconds * 1000L);
    }
}
