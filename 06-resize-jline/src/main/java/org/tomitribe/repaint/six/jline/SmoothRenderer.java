package org.tomitribe.repaint.six.jline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

import java.util.ArrayList;
import java.util.List;

/**
 * Chapter 06's renderer with the plumbing handed to JLine. Compare this
 * file to 06-resize's SmoothRenderer: the frame() assembly, the numLines
 * accounting, the wipe loop, and the escape strings are all gone —
 * org.jline.utils.Display owns climb, per-line diff, and erase, resolved
 * through terminfo capabilities rather than hardcoded sequences.
 *
 * What did NOT move: the store-latest + ticker coalescing (JLine has no
 * frame-rate opinion), the dirty-skip, the height clamp policy, and the
 * lifetime cursor hide + shutdown hook. That remainder is the design.
 */
public class SmoothRenderer implements Renderer {

    private final Terminal terminal;
    private final Display display;
    private final Thread painter;

    private final Thread restoreHook;

    private volatile List<Job> latest;
    private volatile boolean running = true;
    private volatile boolean resized;

    private List<String> lastLines = List.of();

    public SmoothRenderer(final Terminal terminal, final int fps) {
        this.terminal = terminal;
        this.display = new Display(terminal, false); // false: inline, not fullscreen
        this.display.resize(terminal.getSize());
        terminal.handle(Terminal.Signal.WINCH, signal -> resized = true); // push, not poll

        terminal.puts(Capability.cursor_invisible); // lifetime hide (ch05's field-tested choice)
        terminal.flush();
        // An adoption lesson (field-found): this hook pokes an ADOPTED object
        // that polices its lifecycle — after try-with-resources closes the
        // Terminal, puts() throws "Terminal has been closed". So the hook
        // exists only for abnormal exits (Ctrl-C) and close() deregisters it
        // on the orderly path — JLine's own pattern (ShutdownHooks.add in
        // the constructor, .remove in doClose; survey-verified).
        this.restoreHook = new Thread(() -> {
            try {
                terminal.puts(Capability.cursor_normal); // restore on Ctrl-C
                terminal.flush();
            } catch (final RuntimeException ignored) {
                // best effort: the terminal may already be closed
            }
        });
        Runtime.getRuntime().addShutdownHook(restoreHook);

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

        final boolean reflow = resized;
        if (reflow) {
            resized = false;
            display.resize(terminal.getSize()); // Display rewraps its model of the old frame
        }

        final List<String> lines = clamp(Decor.lines(jobs), terminal.getSize().getRows());
        if (!reflow && lines.equals(lastLines)) {
            return; // dirty check: an unchanged frame costs no bytes at all
        }

        final List<AttributedString> styled = new ArrayList<>(lines.size());
        for (final String line : lines) {
            styled.add(new AttributedString(line));
        }
        display.update(styled, -1); // diff against the last frame; write only what changed
        lastLines = lines;
    }

    /** Unchanged from 06-resize: the never-scroll invariant is ours, not JLine's. */
    static List<String> clamp(final List<String> lines, final int height) {
        final int max = Math.max(height - 1, 2);
        if (lines.size() <= max) {
            return lines;
        }
        final List<String> clamped = new ArrayList<>(lines.subList(0, max - 1));
        clamped.add("… " + (lines.size() - (max - 1)) + " more");
        return clamped;
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
        terminal.puts(Capability.cursor_normal); // lifetime-hide ends here
        terminal.flush();
        try {
            Runtime.getRuntime().removeShutdownHook(restoreHook); // orderly close: the hook's job is done
        } catch (final IllegalStateException ignored) {
            // JVM already shutting down — the hook's own try/catch covers us
        }
    }
}
