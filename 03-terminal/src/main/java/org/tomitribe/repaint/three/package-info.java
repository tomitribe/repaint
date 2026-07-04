/**
 * Chapter 03 — ask the terminal before you draw on it.
 *
 * Chapters 01 and 02 drew blind: they assumed a terminal was attached and
 * guessed its width. Pipe them and you get escape-code soup; run them in a
 * narrow window and the block staircases. This chapter asks two questions
 * first —
 *
 *   1. Is this stream even a terminal?   (isatty)
 *   2. If so, how big is it?             (window size)
 *
 * — and introduces the seam the whole library will hang on: a Renderer
 * interface with two implementations, chosen by the answers. A real terminal
 * gets the chapter-02 block painter; a pipe gets an append-only writer whose
 * output reads like a log. Same model, same update calls, degraded output.
 * Every surveyed codebase has this seam (docker's selectEventProcessor,
 * JLine's provider chain + DumbTerminal, bubbletea's nilRenderer); see
 * docs/SURVEY.md, "Two renderers minimum from day one".
 *
 * Java has no isatty() or TIOCGWINSZ of its own. This chapter uses the
 * exec fallback every surveyed Java path keeps in its back pocket
 * (JLine's ExecPty: shell out to test/stty); the real answer — four FFM
 * downcalls — arrives with chapter 06, behind the same Terminal type.
 */
package org.tomitribe.repaint.three;
