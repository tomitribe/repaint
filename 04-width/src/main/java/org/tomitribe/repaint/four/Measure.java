package org.tomitribe.repaint.four;

import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Out;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Measure {

    /**
     * Measure strings three ways, and let your terminal be the judge.
     *
     * Each sample prints as [text] padded to 16 CELLS. If our cell
     * arithmetic agrees with your terminal, the right-hand brackets form a
     * perfect column. Below it, the same rows padded to 16 CHARS — Java's
     * String.format("%-16s") — and the brackets stagger: that ragged edge is
     * chapter 03's arithmetic, docker's production bug, and the reason this
     * chapter exists. Pass your own strings as arguments to test more.
     *
     * @param extra Additional strings to measure alongside the samples
     */
    @Command
    public void measure(@Out final PrintStream out, final String... extra) {
        final List<String> samples = new ArrayList<>(List.of(
                "plain",
                "café",          // é as one code point (NFC)
                "cafe\u0301",         // e + combining accent (NFD) — 5 chars, 4 cells
                "コンニチハ",           // 5 chars, 10 cells
                "日本語",              // 3 chars, 6 cells
                "👍 ok",    // 👍 — 2 chars, 1 code point, 2 cells
                "🚀-deploy" // 🚀-deploy
        ));
        samples.addAll(List.of(extra));

        out.println("chars  cps  cells  padded to 16 cells");
        for (final String s : samples) {
            out.printf("%5d  %3d  %5d  [%s]%n",
                    s.length(),
                    s.codePointCount(0, s.length()),
                    Width.of(s),
                    Width.fit(s, 16));
        }

        out.println();
        out.println("same rows, padded to 16 CHARS (chapter-03 arithmetic) — watch the brackets:");
        for (final String s : samples) {
            out.printf("%18s[%-16s]%n", "", s);
        }
    }
}
