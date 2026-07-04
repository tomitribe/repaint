package org.tomitribe.repaint.six;

/**
 * The one width function. All padding, truncation, and alignment in this
 * module goes through here — that routing discipline, more than the table
 * below, is the chapter's lesson.
 *
 * The algorithm is Markus Kuhn's classic wcwidth(): control chars aside,
 * a code point is 0 cells if it's combining/format (accents, ZWJ), 2 cells
 * if it's East-Asian wide or emoji-presentation, else 1. Java supplies the
 * combining classes via Character.getType(); the wide ranges we carry as a
 * table. Real implementations generate that table from the Unicode data
 * files (EastAsianWidth.txt, emoji-data.txt — see JLine's WCWidth.java,
 * Unicode 16); ours is the classic Kuhn set plus the major emoji blocks,
 * enough to be honest for these demos.
 *
 * Known, deliberate limit: this measures PER CODE POINT. A ZWJ family
 * emoji (man+ZWJ+woman+ZWJ+girl) measures 2+0+2+0+2 = 6 cells here, and
 * renders as anywhere from 2 to 6 depending on the terminal. The full
 * answer is grapheme clustering gated on what the terminal PROVES it does
 * (mode 2027 / probing — see docs/SURVEY.md) — a horizon chapter.
 */
public final class Width {

    private Width() {
    }

    /** Display width of the whole string, in terminal cells. */
    public static int of(final String s) {
        int cells = 0;
        // walk by CODE POINT, never by char — charAt() would hand us half
        // a surrogate pair for anything outside the BMP (every emoji)
        for (int i = 0; i < s.length(); ) {
            final int cp = s.codePointAt(i);
            cells += Math.max(of(cp), 0);
            i += Character.charCount(cp);
        }
        return cells;
    }

    /** Display width of one code point: 0, 1, or 2 (-1 for control chars). */
    public static int of(final int cp) {
        if (cp == 0) {
            return 0;
        }
        if (cp < 32 || (cp >= 0x7F && cp < 0xA0)) {
            return -1; // control chars have no printable width
        }
        switch (Character.getType(cp)) {
            case Character.NON_SPACING_MARK:   // Mn — combining accents
            case Character.ENCLOSING_MARK:     // Me
            case Character.FORMAT:             // Cf — includes ZWJ, ZWSP
                return 0;
            default:
        }
        if (cp >= 0x1160 && cp <= 0x11FF) {
            return 0; // Hangul Jamo medial vowels/final consonants
        }
        return wide(cp) ? 2 : 1;
    }

    /**
     * Truncate to at most {@code cells} display cells — by code point, so a
     * surrogate pair is never split (compare docker's byte-slicing bug,
     * tty.go:513). A 2-cell glyph that doesn't fit is dropped whole.
     */
    public static String truncate(final String s, final int cells) {
        int used = 0;
        int i = 0;
        while (i < s.length()) {
            final int cp = s.codePointAt(i);
            final int w = Math.max(of(cp), 0);
            if (used + w > cells) {
                break;
            }
            used += w;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    /** Truncate then right-pad with spaces to exactly {@code cells} cells. */
    public static String fit(final String s, final int cells) {
        final String truncated = truncate(s, cells);
        return truncated + " ".repeat(cells - of(truncated));
    }

    /* East-Asian Wide/Fullwidth + major emoji-presentation ranges, sorted.
     * The Kuhn classic set plus the emoji blocks modern terminals render
     * at two cells. Flat pairs: {first, last} inclusive. */
    private static final int[] WIDE = {
            0x1100, 0x115F,   // Hangul Jamo initial consonants
            0x231A, 0x231B,   // watch, hourglass
            0x2329, 0x232A,   // angle brackets
            0x23E9, 0x23F3,   // media control emoji
            0x25FD, 0x25FE,   // small squares
            0x2614, 0x2615,   // umbrella, hot beverage
            0x2648, 0x2653,   // zodiac
            0x267F, 0x267F,   // wheelchair
            0x2693, 0x2693,   // anchor
            0x26A1, 0x26A1,   // high voltage
            0x26AA, 0x26AB,   // circles
            0x26BD, 0x26BE,   // sports
            0x26C4, 0x26C5,   // snowman, sun behind cloud
            0x26F2, 0x26F3,   // fountain, flag in hole
            0x26F5, 0x26F5,   // sailboat
            0x26FA, 0x26FA,   // tent
            0x26FD, 0x26FD,   // fuel pump
            0x2705, 0x2705,   // check mark button
            0x270A, 0x270B,   // fists
            0x2728, 0x2728,   // sparkles
            0x274C, 0x274C,   // cross mark
            0x2753, 0x2755,   // question/exclamation
            0x2795, 0x2797,   // math emoji
            0x27B0, 0x27B0,   // curly loop
            0x2B1B, 0x2B1C,   // large squares
            0x2B50, 0x2B50,   // star
            0x2B55, 0x2B55,   // heavy circle
            0x2E80, 0x303E,   // CJK radicals, kana punctuation
            0x3041, 0x33FF,   // hiragana, katakana, CJK symbols
            0x3400, 0x4DBF,   // CJK ext A
            0x4E00, 0x9FFF,   // CJK unified ideographs
            0xA000, 0xA4CF,   // Yi
            0xA960, 0xA97F,   // Hangul Jamo ext A
            0xAC00, 0xD7A3,   // Hangul syllables
            0xF900, 0xFAFF,   // CJK compatibility ideographs
            0xFE10, 0xFE19,   // vertical forms
            0xFE30, 0xFE52,   // CJK compatibility forms
            0xFE54, 0xFE66,   // small form variants
            0xFE68, 0xFE6B,   // small form variants
            0xFF00, 0xFF60,   // fullwidth forms
            0xFFE0, 0xFFE6,   // fullwidth signs
            0x16FE0, 0x16FE4, // ideographic symbols
            0x17000, 0x187F7, // Tangut
            0x1B000, 0x1B2FB, // Kana supplement
            0x1F004, 0x1F004, // mahjong red dragon
            0x1F0CF, 0x1F0CF, // joker
            0x1F18E, 0x1F18E, // AB button
            0x1F191, 0x1F19A, // squared emoji
            0x1F200, 0x1F2FF, // enclosed ideographic supplement
            0x1F300, 0x1F64F, // misc symbols & pictographs, emoticons
            0x1F680, 0x1F6FF, // transport & map emoji
            0x1F900, 0x1F9FF, // supplemental symbols & pictographs
            0x1FA70, 0x1FAFF, // symbols & pictographs ext A
            0x20000, 0x2FFFD, // CJK ext B and beyond
            0x30000, 0x3FFFD, // CJK ext G
    };

    private static boolean wide(final int cp) {
        int lo = 0;
        int hi = WIDE.length / 2 - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (cp < WIDE[mid * 2]) {
                hi = mid - 1;
            } else if (cp > WIDE[mid * 2 + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }
}
