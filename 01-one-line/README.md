# 01 — one line

**Adds:** rewriting a single terminal line in place with `\r`.
**Teaches:** escape-free basics — overwrite don't clear, and why you must flush.

## The starting defect

Run the version everyone writes first:

```
./target/one-line naive
```

Fifty lines of `working... N%` scroll your terminal away. Every live-updating
CLI you've ever used exists because of how bad that looks.

## The mechanism

Two facts carry this whole chapter:

1. **`\r` moves the cursor to column 0 of the current line** — no new line is
   started. Whatever prints next lands on top of what was there. That's the
   entire trick behind a one-line progress bar; no ANSI escape codes yet.
2. **`PrintStream` only flushes on `\n`.** A live line is never "finished," so
   nothing appears unless you `flush()` after every write. Forgetting this is
   the classic first bug — the program runs silently and prints one final
   line at exit.

Try the fixed versions:

```
./target/one-line progress
./target/one-line spinner --message="reticulating splines"
```

The spinner also demonstrates something subtle: it changes *even though
nothing happened*. Animation is time-driven, not event-driven — a truth that
returns in chapter 05 when a paint ticker takes over frame timing.

## This chapter's own defect (on purpose)

```
./target/one-line countdown
```

Watch the final seconds: `10.0s` is one character wider than `9.9s`, and
overwriting never erases — so the orphaned `s` from the wider frame stays on
screen and the line reads `9.9ss`. Now:

```
./target/one-line countdown --pad
```

The line is right-padded with spaces to a fixed width, so the shorter frame
*overwrites* the leftover. **Overwrite-with-padding, not erase-then-write**, is
how every renderer in the survey handles this — compose left-pads its timers
for exactly this reason (`docs/docker-live-terminal-rendering.md` §4, the
timer left-pad at `tty.go:344`), and it's gentler on the eye than erasing,
which flashes.

## What's still broken (→ chapter 02)

- **One line is all you get.** `\r` can't reach the line above. A task list —
  one line per container, all updating — needs the cursor to move *up*, which
  is where ANSI escape codes enter (`ESC [ <n> A`).
- **Pipe it and look:**

  ```
  ./target/one-line progress > /tmp/out; cat -v /tmp/out
  ```

  A wall of `^M`-separated frames. We're emitting terminal control characters
  into a file. Real tools detect whether stdout is a terminal and degrade to
  plain append-only output — that's chapter 03.
- **The width is a guess.** If your terminal is narrower than the progress
  bar, the line wraps and the illusion collapses. Measuring the terminal is
  also chapter 03; using the measurement *correctly* is chapter 04.

## Where this pattern lives in the wild

`docker pull`'s layer lines are this chapter plus cursor-up: each update is
`erase line, \r, rewrite` (`docs/docker-live-terminal-rendering.md` §3 —
`jsonmessage.go`, the `Display` function). JLine and Bubble Tea never render
this way — they diff — but their output still bottoms out in the same two
facts: bytes to a stream, flushed when the frame is done.
