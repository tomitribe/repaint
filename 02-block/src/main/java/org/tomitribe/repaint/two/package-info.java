/**
 * Chapter 02 — a block of lines, repainted in place.
 *
 * One escape sequence joins the toolbox: CSI cursor-up, {@code ESC [ n A}
 * (bytes {@code [<n>A}). With it, the '\r' trick from chapter 01
 * extends to many lines: move up over the block you printed last frame,
 * rewrite every line, let the newlines walk you back down.
 *
 * The whole model hangs on one number: how many physical lines did the
 * PREVIOUS frame print? That is how far up to go. Three of this chapter's
 * commands exist to show what happens when that accounting is wrong
 * (eat, shrink, wrap); one shows it done right (tasks).
 *
 * Landmine to memorize now: {@code ESC[0A} does not mean "up zero" — a CSI
 * parameter of 0 is treated as the default, 1. Moving up zero lines means
 * emitting NOTHING. Every surveyed codebase guards this; see docs/SURVEY.md
 * ("Guard CSI parameter 0").
 */
package org.tomitribe.repaint.two;
