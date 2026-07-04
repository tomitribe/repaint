/**
 * Chapter 05 — two rates, one writer, zero flicker.
 *
 * The big idea: a program has TWO natural rates and they must not be
 * coupled. The MESSAGE rate is how fast the model changes — a build tool
 * can update progress a million times a second. The FRAME rate is how fast
 * a human can watch — 10 to 60 paints a second is plenty. Chapter 04
 * coupled them (every update() painted); this chapter splits them:
 * update() just stores the latest state, and a painter ticker flushes it
 * at fps. Frames in between are dropped, never queued. Bubble Tea proved
 * this shape (docs/SURVEY.md, "Coalesce with store-latest + a paint
 * ticker"); we add the improvement the survey suggests — the frame is
 * BUILT on the tick too, and skipped entirely when identical to the last.
 *
 * The rest is the anti-flicker checklist. Once, at the first frame:
 *
 *   ESC[?25l     hide cursor — for the renderer's LIFETIME, not per frame
 *
 * then per frame, assembled into ONE buffered write:
 *
 *   ESC[?2026h   begin synchronized update — terminal buffers until ESU
 *   ESC[nA \r    the chapter-02 climb
 *   ...lines...  overwrite, padded to width (chapters 01/04)
 *   ESC[?2026l   end synchronized update — render atomically
 *
 * and once, at close (backed by a shutdown hook for Ctrl-C):
 *
 *   ESC[?25h     show cursor
 *
 * Why lifetime and not per frame: showing the cursor between frames parks
 * it, blinking, on the line below the block — compose has exactly that
 * artifact; bubbletea hides for the program's lifetime. The price is
 * restore discipline: the cursor MUST come back on every exit path. A
 * shutdown hook covers normal exit and Ctrl-C; kill -9 has no cure —
 * if your cursor ever goes missing:  printf '\x1b[?25h'
 *
 * Mode 2026 is emitted blind: terminals that don't support it ignore it
 * (JLine ships exactly this; docs/SURVEY.md, "Adopt ?2026, emitted
 * blind").
 */
package org.tomitribe.repaint.five;
