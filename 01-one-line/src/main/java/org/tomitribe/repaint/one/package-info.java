/**
 * Chapter 01 — one line, rewritten in place.
 *
 * The entire mechanism of this chapter is two facts:
 *
 *   1. '\r' (carriage return) moves the cursor to column 0 of the CURRENT
 *      line without starting a new one. Whatever is printed next overwrites
 *      what was there.
 *   2. PrintStream only flushes on '\n'. A line that is never "finished"
 *      with a newline must be flushed by hand or nothing appears at all.
 *
 * One command per class, one lesson per command: run them in order —
 * naive, progress, spinner, countdown.
 */
package org.tomitribe.repaint.one;
