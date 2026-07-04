# 03 — terminal

**Adds:** isatty + window size, and the renderer seam.
**Teaches:** ask the terminal before you draw on it — and what to do when the
answer is "there is no terminal."

## The starting defects

Chapter 02 drew blind, twice over:

```
./02-block/target/block tasks | cat -v     # escape-code soup in the pipe
./02-block/target/block tasks              # in a window under 80 cols: stairs
```

## The mechanism

Two questions, asked once at startup:

1. **Is stdout a terminal?** Java 17 has no `isatty()`, so we shell out:
   `sh -c 'test -t 1'` with stdout *inherited* — the child's fd 1 **is** ours,
   so its answer is about ours. Exit 0 means terminal.
2. **How big is it?** `stty size` prints `rows cols` for the terminal on *its*
   stdin (inherited from ours), falling back to `COLUMNS`/`LINES`, then 80×24.

```
./target/terminal probe
./target/terminal probe | cat          # fd 1 is a pipe now — watch it flip
./target/terminal probe < /dev/null    # stty loses its terminal — fallback chain
```

That third run is a scar reproduced on purpose: `stty size` measures **stdin's**
terminal while we paint **stdout** — usually the same terminal, but not always.
docker-compose has exactly this class of bug in production: it paints stderr but
sizes a hardcoded stdout, so `compose up > file` collapses to 80×24 on a wide
terminal (`docs/SURVEY.md`, "Measure size on the same fd you draw on"). The
honest fix is `ioctl(TIOCGWINSZ)` on the *exact* fd — four FFM downcalls,
arriving in chapter 06 behind this same `Terminal` type.

## The seam

The chapter's real payload is an interface:

```java
public interface Renderer {
    void update(List<Job> jobs);   // the model crosses; decoration doesn't
    void close();
}
```

selected once, up front, by the probe — `Renderer.of(out, terminal)` returns
the chapter-02 block painter (now at the *measured* width) for a terminal, or
an append-only `PlainRenderer` for a pipe. Every surveyed codebase has this
seam: docker's `selectEventProcessor`, JLine's provider chain ending in
`DumbTerminal`, bubbletea's `nilRenderer` (`docs/SURVEY.md`, "Two renderers
minimum from day one").

```
./target/terminal tasks                    # block renderer, real width — no stairs
./target/terminal tasks | cat              # same producer, readable event log
./target/terminal tasks --renderer=plain   # the escape hatch (docker: --progress=plain)
```

Note what the pipe output looks like: one line per **status change**, not per
frame. That's the second lesson hiding in the seam: the model that crosses it
carries *no spinner and no ticking timer* — decoration is computed by the block
renderer at paint time, because only a renderer that repaints can afford
content that changes every frame. Compose draws the line in the same place
(its events carry status; the ttyWriter computes elapsed from `startTime` each
frame). Get this boundary wrong and plain mode floods like `naive` did.

## What's still broken (→ chapters 04, 05, 06)

- **`fit()` counts Java `char`s, not terminal cells.** The measurement is
  honest now but the arithmetic on it isn't — **chapter 04**.
- **Flicker** at high fps: per-line unbuffered writes, visible cursor —
  **chapter 05**.
- **The size is a snapshot.** Resize the window mid-run and the block is wrong
  until restart; and each `detect()` costs two forked processes — both fixed
  by FFM (`ioctl` per frame is nearly free, SIGWINCH pushes) in **chapter 06**.
