package org.tomitribe.repaint.six.jline;

import org.jline.utils.AttributedString;

/**
 * Chapter 04's 180-line Width, reduced to an adapter: JLine's
 * AttributedString measures in cells (wcwidth, Unicode 16 tables — the
 * very code the survey reviewed). Same two methods, same call sites in
 * Decor; only the guts changed.
 */
public final class Width {

    private Width() {
    }

    /** Display width in terminal cells. */
    public static int of(final String s) {
        return new AttributedString(s).columnLength();
    }

    /** Truncate to at most {@code cells}, then right-pad to exactly {@code cells}. */
    public static String fit(final String s, final int cells) {
        final AttributedString as = new AttributedString(s);
        final String truncated = as.columnLength() <= cells
                ? s
                : as.columnSubSequence(0, cells).toString();
        return truncated + " ".repeat(cells - of(truncated));
    }
}
