# 02 — block

**Adds:** a multi-line block repainted in place — the first ANSI escape code.
**Teaches:** the `numLines` accounting the whole fixed-block model hangs on,
and three ways it goes wrong.

## The starting defect

Chapter 01 could rewrite one line, because `\r` can't leave it. A task list —
one line per container, all updating at once — needs the cursor to move *up*.

## The mechanism

One escape sequence joins the toolbox:

| Sequence | Bytes | Meaning |
|---|---|---|
| cursor up *n* | `ESC [ n A` (`[3A`) | move cursor up *n* rows, same column |

With it, the whole model is: **move up over the block you printed last frame,
rewrite every line padded to full width, and the newlines walk you back
down.** The only state is one integer — how many physical lines the *previous*
frame printed. That's the model `docker compose up` ships
(`docs/docker-live-terminal-rendering.md` §4, `tty.go`).

```
./target/block tasks
```

Six parallel workers, one painter. The concurrency shape matters more than the
escape code: **producers mutate the model and never touch the terminal; a
single painter loop owns the screen.** Every surveyed codebase converges on
this (`docs/SURVEY.md`, concurrency row). Note also the spinner: its frame
comes from wall-clock elapsed time, not the paint tick, so `--fps 3` spins at
the same speed as `--fps 60` — compose gets this wrong (its spinner clock is
never reset, so spinner speed = paint rate; SURVEY "Decouple spinner timing").

## Three ways the accounting breaks (each on purpose)

**1. The first frame** — there's nothing above you yet:

```
./target/block eat            # watch it destroy the lines above
./target/block eat --latch    # the fix: move up by the PREVIOUS count, which starts at 0
```

A painter that moves up by the *current* frame's line count climbs into your
scrollback on frame one and repaints it. Compose guards this with a one-shot
latch (`repeated`, `tty.go:303-307`). Related landmine, memorize it now:
`ESC[0A` does **not** mean "up zero" — CSI parameters of 0 mean the default,
1. Moving up zero lines means emitting *nothing* (SURVEY: "Guard CSI
parameter 0").

**2. The block shrinks** — chapter 01's countdown defect gone vertical:

```
./target/block shrink           # orphaned rows linger as the block shrinks
./target/block shrink --wipe    # blank them out — and COUNT the blanks
```

The wipe lines must be counted in `numLines` or the next frame's climb is
short. Compose: `tty.go:380-384`.

**3. A line wraps** — the accounting is in logical lines, the terminal deals
in physical rows:

```
./target/block wrap             # one 200-char line, and the block staircases
```

One line wider than your terminal occupies two rows; cursor-up climbs one too
few; the block walks down your screen leaving a trail. This is *the* reason
fixed-block renderers truncate to terminal width before printing (compose
iterates truncation until every line fits, `tty.go:415-433`) — and why the
renderer must know the terminal width at all.

## What's still broken (→ chapters 03, 04, 05)

- **`--width 80` is a guess.** Run `tasks` in a terminal narrower than 80 and
  you get `wrap`'s stairs for free. Measuring the real width (and noticing
  you're not attached to a terminal at all — try piping it) is **chapter 03**.
- **The truncation is a lie waiting to happen.** `pad()` counts Java `char`s;
  the spinner glyphs already occupy one *cell* but there are glyphs that take
  two. **Chapter 04** makes width honest.
- **Flicker.** `tasks --fps 60` writes each line as its own unbuffered print
  with the cursor visible — on a slow terminal you can catch the cursor
  racing and half-painted frames. The anti-flicker checklist is **chapter 05**.
