/**
 * Chapter 06 — the syscalls, finally: FFM ends the guessing.
 *
 * Chapter 03 shelled out to `test -t 1` and `stty size` — two forked
 * processes per probe, measuring the wrong fd in edge cases, and a size
 * that was a startup snapshot. This chapter replaces all of it with three
 * libc calls made directly from Java via the Foreign Function &amp; Memory
 * API (final in Java 22):
 *
 *   isatty(fd)                 is this fd a terminal?
 *   ioctl(fd, TIOCGWINSZ)      how big — asked of the EXACT fd we draw on,
 *                              cheap enough to re-ask any time
 *   sigaction(SIGWINCH)        resize becomes a push: the kernel tells us
 *
 * Two renderer upgrades ride along, both fixes for field-found wreckage:
 * the HEIGHT CLAMP (a block taller than the window used to shear and pump
 * frame fragments into scrollback every tick — now excess rows collapse
 * into "… N more", compose's defense, tty.go:325), and LIVE RESIZE (on
 * SIGWINCH: re-measure, erase the block, repaint at the new size).
 *
 * The full native blueprint this chapter is distilled from — per-OS
 * struct layouts, signal dispatch, Windows — is JLine's CLibrary and
 * FfmSignalHandler; see docs/jline3-live-terminal-rendering.md §(c).
 */
package org.tomitribe.repaint.six;
