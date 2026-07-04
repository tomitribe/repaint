# 05 — smooth

**Adds:** the anti-flicker checklist — whole-frame buffering, hidden cursor,
synchronized update — and the store-latest + ticker split between message
rate and frame rate.
**Teaches:** a program has two natural rates, and coupling them was the bug.

## The starting defect

Chapter 04 painted on every update call: one write per line, cursor visible
the whole way, repainting even when nothing changed.

## If you don't see any flicker — read this first

Run `tasks --naive --fps 60` locally and you'll probably see… nothing wrong.
That's the real lesson: **flicker is a transport phenomenon, not a
byte-count phenomenon.** Your local emulator receives the whole 2.5KB frame
in one gulp from the kernel's pty buffer and renders it in a single pass —
there is no moment when half a frame exists on screen. The naive painter
tears where bytes *trickle in over time*: laggy ssh, tmux under load, slow
terminals. That's why every surveyed tool pays for the checklist anyway —
they don't get to choose their users' terminals.

So this chapter ships the transport:

```
./target/smooth tasks --naive --slow --tasks 12    # bytes trickle: the frame tears
./target/smooth tasks --slow --tasks 12            # same trickle, held by ?2026
```

`--slow` wraps the output stream in a throttle (64-byte chunks, 2ms apart —
a rough dial-up-grade ssh session). The naive painter visibly assembles each
frame on screen. The smooth painter trickles the *same* bytes — but they're
bracketed by synchronized-update marks, so a 2026-capable terminal (iTerm2,
kitty, WezTerm, Ghostty…) holds them and renders atomically. On a terminal
without 2026 (Apple Terminal), you'll still see the hidden cursor earn its
keep, but partial frames appear — that's precisely why bubbletea probes for
2026 support and JLine emits it blind and shrugs (`docs/SURVEY.md`).

Note the throttle is applied at the seam — neither renderer knows it exists.

## Two rates

The model changes at **message rate** — a build tool can bump progress a
million times a second. A human watches at **frame rate** — 10–60 paints a
second is plenty. The fix is bubbletea's, verbatim from the survey
(`docs/SURVEY.md`, "Coalesce with store-latest + a paint ticker"):
`update()` stores the latest model and returns; a painter thread ticks at
fps and paints whatever is current; frames in between are **dropped, never
queued**. We add the improvement the survey flags: bubbletea still *builds*
a frame per message and discards it — we build on the tick, and skip the
write entirely when the frame equals the last one painted.

```
./target/smooth storm
```

Three workers hammer progress counters as fast as the CPU allows. The exit
summary is the whole argument in four numbers:

```
model updates:     61,847,203
paint ticks:       47
frames painted:    44
frames skipped:    3 (built, compared, unchanged — zero bytes)
updates per frame: 1,405,618
```

Raise `--fps 60` and watch `frames skipped` climb: the timer column only
changes every 0.1s, so at 60 Hz most ticks build an identical frame and cost
zero bytes. That's the dirty check earning its keep.

## The frame, byte by byte

Two escape pairs join the toolbox — both DEC *private modes* (the `?`):

| Sequence | Bytes | Meaning |
|---|---|---|
| hide / show cursor | `ESC[?25l` / `ESC[?25h` | no visible cursor racing during the repaint |
| synchronized update | `ESC[?2026h` / `ESC[?2026l` | terminal buffers everything between the marks, renders atomically |

The per-frame bytes — sync-begin, climb, lines, wipes, sync-end — are
assembled in one `StringBuilder` and written with **one call**, so there is
no moment mid-frame for a slow terminal to display half a repaint. Mode 2026
is **emitted blind** — terminals that don't support it ignore the unknown
mode, which is exactly how JLine ships it (`docs/SURVEY.md`; bubbletea
probes first, and its allow-list scars — Apple Terminal, ssh — are
documented there too).

**The cursor is hidden for the renderer's lifetime, not per frame** — a
choice field-tested on Apple Terminal, where per-frame hide/show produced a
new artifact: no racing (the hide works), but between frames the cursor sat
parked and blinking on the line below the block. `docker compose up` has
exactly that artifact; bubbletea hides for the program's lifetime, and now
so do we. The price is **restore discipline** — the cursor must come back on
*every* exit path. `close()` shows it; a JVM shutdown hook covers Ctrl-C;
`kill -9` has no cure. If your cursor ever goes missing:

```
printf '\x1b[?25h'
```

## The shutdown bug you will write

The first version of this chapter shipped it (field-caught on Apple
Terminal): `close()` interrupted the painter to cut its sleep short — but
with `--slow` the painter was usually mid-*write*, the throttle treated the
interrupt as "stop", and the frame was **truncated**. The dirty check had
already recorded the frame as painted, so the corrective repaint was
skipped. Net effect: a missing row, and the shell prompt printed *inside*
the block where the truncated write stranded the cursor.

Two rules fix it, and they generalize to any renderer with a painter
thread:

1. **An interrupt may shorten sleeps, never truncate bytes.** A frame's
   write is atomic-or-nothing from the program's side; `Throttle` now
   finishes delivery at full speed when interrupted.
2. **The final frame is forced past the dirty check.** The bookkeeping
   ("I painted X") and the truth ("X's bytes all left") can disagree at
   shutdown; one authoritative repaint closes the gap.

Clean shutdown is part of the frame protocol, not an afterthought.

## Golden frames

`SmoothRenderer.frame()` is a pure function from (lines, previous count,
width) to the exact bytes the terminal receives — so `FrameTest` pins the
entire escape choreography with `assertEquals`, including the chapter-02
landmines: no `ESC[0A` on the first frame, wipe rows on shrink, and
chapter 04's cell-fitting riding along. `mvn test` runs it.

## What's still broken (→ chapter 06)

- **The size is still a startup snapshot.** Resize mid-run and the block
  paints at the old width until restart.
- **`stty` still measures the wrong fd in edge cases**, and forking two
  processes per probe is the wrong price. FFM `ioctl(TIOCGWINSZ)` on the
  exact fd, plus SIGWINCH so resize *pushes* — **chapter 06**.
