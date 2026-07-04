package org.tomitribe.repaint.five;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Simulates a slow link: bytes leave in small chunks with a delay between
 * them, the way frames actually arrive over a laggy ssh session or a busy
 * tmux. Flicker is a TRANSPORT phenomenon — locally, a whole frame lands
 * in the kernel's pty buffer at once and the emulator renders it in one
 * pass, so the naive painter looks fine. Stretch the bytes out in time and
 * the difference between the two renderers appears: the naive one tears,
 * because the terminal renders whatever fraction of the frame has arrived;
 * the smooth one is bracketed by ?2026, so a supporting terminal holds the
 * partial frame and renders it atomically.
 */
final class Throttle extends FilterOutputStream {

    private final int chunk;
    private final long delayMs;

    Throttle(final OutputStream out, final int chunk, final long delayMs) {
        super(out);
        this.chunk = chunk;
        this.delayMs = delayMs;
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        // Interruption must never truncate a frame: a half-written frame
        // strands the cursor mid-block and desyncs the climb accounting
        // (field-tested: the shell prompt printed INSIDE the block). On
        // interrupt we stop sleeping and deliver the remaining bytes at
        // full speed — drop the delays, never the data.
        boolean interrupted = Thread.interrupted();
        int written = 0;
        while (written < len) {
            final int n = Math.min(chunk, len - written);
            out.write(b, off + written, n);
            out.flush(); // each chunk reaches the terminal before the pause
            written += n;
            if (!interrupted && written < len) {
                try {
                    Thread.sleep(delayMs);
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
