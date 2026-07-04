# Repaint

Live, in-place terminal rendering in Java — the mechanism behind `docker pull`
layer bars, `docker compose up` task lists, and full TUI frameworks — built up
**one module at a time**.

> The name `repaint` is a working title. The primitive everything here rests on:
> *move the cursor back over lines you already printed, rewrite them in place,
> move on.* There is no framework underneath any of this — it is escape codes
> written to a stream, plus a handful of syscalls.

## How this repo works

Each numbered module is a **standalone CLI** (shaded uber-jar, runnable
directly) that adds exactly one mechanism layer. The progression is
**defect-driven**: every module's README starts with "run the previous module,
watch it break like *this*" — then names the technique that fixes it and cites
the real codebase it was taken from.

The techniques aren't invented here. They come from a source-verified survey of
how three ecosystems do this — Docker CLI/Compose (Go), JLine 3 (Java), and
Bubble Tea v1/v2 (Go) — every claim checked against source with `file:line`
references. The survey lives in [`docs/`](docs/): start with
[`docs/SURVEY.md`](docs/SURVEY.md) for the cross-codebase matrix and the design
decisions each module implements.

## The ladder

| Module | Adds | Teaches | The defect it fixes / exposes |
|---|---|---|---|
| [`01-one-line`](01-one-line/) | `\r` + overwrite one status line | escape codes are just strings; overwrite, don't clear; why you must flush | exposes: stale trailing chars on shrink; one line only; piped output is garbage |
| [`02-block`](02-block/) | fixed multi-line block: cursor-up N, padded rewrite, first-frame latch, shrink-wipe, paint ticker | compose's model: `numLines` accounting, the `ESC[0A` landmine, single-painter concurrency | exposes: flicker, cursor racing, hardcoded size, still garbage when piped |
| [`03-terminal`](03-terminal/) | isatty + winsize (`stty` exec; FFM `ioctl` lands in 06) + a plain append-only renderer | the seam: renderer chosen by probing the stream you draw on; the model crosses, decoration doesn't | fixes: pipes/CI; introduces the public API shape |
| [`04-width`](04-width/) | one wcwidth-based width function behind all padding/truncation + golden-string tests | bytes vs chars vs codepoints vs clusters vs **cells**; Java's `String.length()` trap | fixes: CJK/emoji misalignment (docker's latent bug, reproduced first: `tasks --fit=chars`) |
| [`05-smooth`](05-smooth/) | whole-frame buffer + single flush, hide-cursor, `?2026`, store-latest + dirty-skip coalescing | the anti-flicker checklist; the message-rate/frame-rate split (`storm` proves it in four numbers) | fixes: 02's flicker; fast producers |
| `06-resize` | SIGWINCH via FFM `sigaction`, height clamp + "… N more", reflow | live resize; why clamping keeps the cursor math valid | fixes: resize corruption |

Horizon (once the core feels understood): `07-diff` (JLine's per-line
prefix/suffix diff), `08-input` (raw mode, `/dev/tty`, restore discipline),
`09-pinned` (DECSTBM status bar, `insertAbove` logs), `10-probe` (DECRQM batch
probe, the emoji cursor-displacement trick).

## Building and running

Each module builds a shaded, executable jar:

```
mvn clean install
./01-one-line/target/one-line spinner
```

(Requires Java 17+. The FFM-based modules from `06-resize` onward will need
Java 22+ for their native path; each carries a pure-Java fallback.)
