package org.tomitribe.repaint.four;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-string tests: because layout is computed as pure string
 * arithmetic before any byte reaches a terminal, the whole chapter is
 * testable with assertEquals. Compose tests its renderer the same way
 * (golden frames in tty_test.go) — this is the payoff of keeping frame
 * construction pure.
 */
public class WidthTest {

    @Test
    public void ascii() {
        assertEquals(3, Width.of("abc"));
    }

    @Test
    public void cjkIsTwoCellsPerGlyph() {
        assertEquals(10, Width.of("コンニチハ")); // 5 chars, 10 cells
        assertEquals(6, Width.of("日本語"));
    }

    @Test
    public void combiningMarkIsZeroCells() {
        assertEquals(4, Width.of("cafe\u0301")); // NFD: 5 chars, 4 cells
        assertEquals(4, Width.of("café"));       // NFC: 4 chars, 4 cells
    }

    @Test
    public void emojiIsOneCodePointTwoCells() {
        final String thumbsUp = "👍";
        assertEquals(2, thumbsUp.length());                       // UTF-16 lies
        assertEquals(1, thumbsUp.codePointCount(0, 2));           // code points lie too
        assertEquals(2, Width.of(thumbsUp));                      // cells
    }

    @Test
    public void truncateNeverSplitsAGlyph() {
        // コ=2 ン=2 ニ=2: at 5 cells only コン (4 cells) fits — ニ is dropped whole
        assertEquals("コン", Width.truncate("コンニチハ", 5));
        // never cuts a surrogate pair in half
        assertEquals("", Width.truncate("👍", 1));
    }

    @Test
    public void fitPadsToExactCells() {
        assertEquals("コン ", Width.fit("コンニチハ", 5)); // 4 cells of glyph + 1 space
        assertEquals("abc  ", Width.fit("abc", 5));
    }

    @Test
    public void chapterThreeArithmeticStaggers() {
        // the exhibit: char-padding thinks these are the same width
        final String a = BlockRenderer.chapterThreeFit("デプロイ", 10);
        final String b = BlockRenderer.chapterThreeFit("check", 10);
        assertEquals(a.length(), b.length());       // same CHAR count...
        assertEquals(14, Width.of(a));               // ...but 14 cells
        assertEquals(10, Width.of(b));               // ...vs 10 cells
    }
}
