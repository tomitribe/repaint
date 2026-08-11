/**
 * Chapter 06j — the adoption experiment: chapter 06 rebuilt on JLine.
 *
 * Same three commands, same behavior, same seam. The question this module
 * answers by existing: if you adopt the incumbent instead of writing the
 * plumbing, WHAT DROPS — and what survives because it was never plumbing?
 *
 * Drops (JLine provides it):
 *   Libc.java            three FFM downcalls + SIGWINCH machinery →
 *                        TerminalBuilder + Terminal.handle(Signal.WINCH);
 *                        the native layer arrives as JLine's provider chain
 *                        (ffm on 22+, exec fallback) selected AT RUNTIME
 *   Terminal.java        org.jline.terminal.Terminal
 *   Width.java (180 ln)  a ~25-line adapter over AttributedString.columnLength
 *   frame()/numLines     org.jline.utils.Display owns climb, diff, wipes —
 *                        and diffs PER LINE, so chapter 08 arrives for free
 *   the JDK-22 build     this module compiles at Java 17; FFM is a runtime
 *                        upgrade inside JLine — so the crest descriptor works
 *                        again and javadoc-as-help returns
 *
 * Survives (it was design, not plumbing):
 *   the seam             Renderer / Job / Decor — unchanged, line for line
 *   coalescing           store-latest + fps ticker + dirty-skip; JLine is
 *                        event-driven and has no opinion about frame rate
 *   the height clamp     "… N more" is policy; JLine's Display clamps
 *                        silently (survey-verified) — no summary line
 *   lifetime cursor hide + the shutdown hook — our field-tested choice
 *
 * Traded away (the honest column):
 *   ?2026 sync update    JLine's Display emits it only in fullscreen mode;
 *                        our inline frames lose it (survey: Display.java:452)
 *   byte-golden tests    frame() was a pure function; Display writes to the
 *                        terminal — FrameTest has no equivalent here
 */
package org.tomitribe.repaint.six.jline;
