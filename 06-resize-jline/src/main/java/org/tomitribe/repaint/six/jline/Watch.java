package org.tomitribe.repaint.six.jline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;

import java.io.IOException;
import java.io.PrintStream;

public class Watch {

    /**
     * Resize as a push, one line per change — 06's watch on JLine.
     *
     * Terminal.handle(Signal.WINCH, ...) replaces our sigaction + upcall
     * stub + dispatcher thread; on Windows (where SIGWINCH doesn't exist)
     * JLine synthesizes the same event from console input, which our
     * Libc never could.
     *
     * @param seconds How long to watch before exiting
     */
    @Command
    public void watch(@Out final PrintStream out,
                      @Option("seconds") @Default("30") final int seconds) throws IOException, InterruptedException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            final Size initial = terminal.getSize();
            out.println("now: " + initial.getColumns() + "x" + initial.getRows()
                    + " — drag the window corner (" + seconds + "s)...");
            terminal.handle(Terminal.Signal.WINCH, signal -> {
                final Size size = terminal.getSize();
                out.println("WINCH → " + size.getColumns() + "x" + size.getRows());
            });
            Thread.sleep(seconds * 1000L);
        }
    }
}
