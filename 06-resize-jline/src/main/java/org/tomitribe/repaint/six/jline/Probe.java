package org.tomitribe.repaint.six.jline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Out;

import java.io.IOException;
import java.io.PrintStream;

public class Probe {

    /**
     * Chapter 06's probe, answered by JLine's provider chain.
     *
     * The interesting line is the terminal class: JLine tries its providers
     * in order (ffm, jni, exec) and takes the first that works — so the
     * same binary gets FFM syscalls on Java 22+ and the stty fallback
     * elsewhere, decided at runtime. That's the multi-jar provider
     * architecture, delivered by the incumbent.
     */
    @Command
    public void probe(@Out final PrintStream out) throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            final Size size = terminal.getSize();
            out.println("java:     " + Runtime.version().feature());
            out.println("terminal: " + terminal.getClass().getSimpleName() + " (type " + terminal.getType() + ")");
            out.println("tty:      " + !Terminal.TYPE_DUMB.equals(terminal.getType()));
            out.println("size:     " + size.getColumns() + "x" + size.getRows());
        }
    }
}
