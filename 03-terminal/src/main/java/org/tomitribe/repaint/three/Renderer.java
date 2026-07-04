package org.tomitribe.repaint.three;

import java.io.PrintStream;
import java.util.List;

/**
 * The seam. Producers maintain a model of Jobs and never touch the
 * terminal; on each tick the current model is handed to whichever
 * renderer was selected — once, up front — by probing the stream we're
 * about to draw on.
 *
 * This is the discipline every surveyed codebase converges on: docker's
 * selectEventProcessor picks Full/Plain/Quiet/JSON off one probe of the
 * stream it paints; bubbletea swaps in a nilRenderer; JLine falls back to
 * a DumbTerminal. The same model drives every implementation — TTY-ness
 * degrades the output, never the program. See docs/SURVEY.md, "Two
 * renderers minimum from day one".
 */
public interface Renderer {

    /** Render the model's current state. Called once per tick. */
    void update(List<Job> jobs);

    /** Paint any final state and leave the stream tidy. */
    void close();

    /** The seam itself: probe, then pick. */
    static Renderer of(final PrintStream out, final Terminal terminal) {
        return terminal.tty() ? new BlockRenderer(out, terminal.width()) : new PlainRenderer(out);
    }
}
