package org.tomitribe.repaint.three;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;

public class Probe {

    /**
     * Report what we can learn about the terminal — then pipe it and compare.
     *
     * Run it bare and you should see your real terminal dimensions, measured
     * by `stty size`. Then run `probe | cat`: fd 1 is now a pipe, isatty says
     * no, and the size falls back to the 80x24 default. This one boolean is
     * the seam the whole library pivots on — the tasks command picks its
     * renderer off exactly this probe.
     */
    @Command
    public void probe(@Out final PrintStream out) {
        final Terminal terminal = Terminal.detect();
        out.println("tty:    " + terminal.tty() + "  (sh -c 'test -t 1' on inherited stdout)");
        out.println("width:  " + terminal.width());
        out.println("height: " + terminal.height());
        out.println("source: " + terminal.sizeSource());
        if (terminal.tty()) {
            out.println();
            out.println("now try:  probe | cat        (fd 1 becomes a pipe)");
            out.println("and:      probe < /dev/null  (stty loses its terminal, env/default kicks in)");
        }
    }
}
