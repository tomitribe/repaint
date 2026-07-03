# Live-Update Terminal Rendering in the Docker CLI

A field guide to how `docker` and `docker compose` turn a write-once stdout into a
repainting, app-like surface. Written as a design reference for building an
equivalent Java library — so it focuses on the *driving mechanism*, not the
feature set.

Everything here is drawn from two repos in this workspace:

- `cli/` — the classic `docker pull`/`push` layered progress (origin of the technique).
- `compose/` — a richer multi-task TTY UI plus an interactive bottom-menu overlay.

There is **no framework** underneath any of this. It is raw ANSI escape codes
written to an `io.Writer`, plus a `GetWinsize` syscall. That is the whole trick.
Two small third-party libs only wrap the codes: `github.com/morikuni/aec`
(escape-sequence builder) and `github.com/buger/goterm` (width/height).

---

## 1. Three strategies, one primitive

All three share one primitive: **move the cursor up over lines you already
printed, rewrite them in place, move back down.** The terminal has no "erase and
redraw" API — you reposition the cursor and overwrite. They differ in *how much*
they repaint and *when*.

| Strategy | Where | Repaint scope | Driven by | Best for |
|---|---|---|---|---|
| **A. Sparse dirty-line** | `cli` jsonmessage | one line per update | incoming events | many independent rows (layers), unknown count |
| **B. Fixed-block full repaint** | `compose` `ttyWriter` | the whole block, every tick | a 100ms ticker | a bounded task list with spinners/timers |
| **C. Bottom overlay** | `compose` keyboard menu | one anchored region at screen bottom | key events + log flushes | a persistent status/menu bar over scrolling logs |

The rest of the doc details each, then answers your specific questions
(window size, frequency, cursor, flicker, overflow) in a cross-cutting table.

---

## 2. The escape-code vocabulary

These are the only codes in play. `CSI` = `ESC [` = `\x1b[`.

| Purpose | Sequence | Source of truth |
|---|---|---|
| Erase entire line | `\x1b[2K` | `jsonmessage.go:107` (`ansiEraseLine`) |
| Erase line to end (tail) | `\x1b[0K` | `aec.EraseLine(Tail)` — `ansi.go` `clearLine()` |
| Cursor up N | `\x1b[<N>A` | `jsonmessage.go:107` / `aec.Up` |
| Cursor down N | `\x1b[<N>B` | `jsonmessage.go:108` / `aec.Down` |
| Cursor to column C (1-based) | `\x1b[<C>G` | `aec.Column(0)` — used as "carriage return" |
| Carriage return (col 0) | `\r` | `jsonmessage.go:139` |
| Absolute position row;col | `\x1b[<row>;<col>H` | `aec.Position` — `ansi.go` `moveCursor()` |
| Hide cursor | `\x1b[?25l` | `aec.Hide` — `tty.go:309` |
| Show cursor | `\x1b[?25h` | `aec.Show` — `tty.go:315` |
| Save cursor position | `\x1b[s\x1b7` (emits **both** SCO + DEC) | `aec.Save` — `ansi.go` `saveCursor()`; verified `vendor/.../aec/aec.go:133` |
| Restore cursor position | `\x1b[u\x1b8` (emits **both** SCO + DEC) | `aec.Restore` — `ansi.go` `restoreCursor()`; verified `aec.go:135` |
| OSC 8 hyperlink | `\x1b]8;;<url>\x1b\\<text>\x1b]8;;\x1b\\` | `ansi.go` `OSC8Link()` |

Note the deliberate choice in Strategy A: it uses `\r` + erase-line rather than
absolute positioning, so it never needs to know its absolute row — only *relative*
offsets. That makes it resilient to the terminal scrolling.

---

## 3. Strategy A — sparse dirty-line (`docker pull`)

**File:** `cli/vendor/github.com/moby/moby/client/pkg/jsonmessage/jsonmessage.go`
(vendored from moby; this is the canonical implementation the CLI links against).

The daemon streams newline-delimited JSON messages, each with an `ID` (the layer),
a `Status`, and an optional `Progress`. The renderer keeps a map from ID to the
line offset where that ID was first printed:

```go
ids := make(map[string]uint)          // id -> line index (0 = oldest)
```

Per message (`displayJSONMessages`, `jsonmessage.go:245`):

1. **New ID** → assign `line = len(ids)`, print a `\n` to open a fresh line at the
   bottom. The map size *is* the number of live rows.
2. **Known ID** → compute `diff = len(ids) - line` (how many rows up this ID is),
   `cursorUp(diff)`, rewrite that single line, then `cursorDown(diff)` back to the
   bottom.
3. Rewriting a line (`Display`, `jsonmessage.go:132`): `\x1b[2K` (erase whole
   line) → `\r` → print `ID: status <progressbar>`. No newline; the cursor stays
   on that line so the following `cursorDown` lands correctly.

Key properties:

- **Only the changed line is touched.** No full-screen repaint. This is inherently
  flicker-free because untouched rows are never rewritten.
- **Relative motion only.** It never asks "what row am I on?" — just "how far up is
  this layer from the newest?" So if the terminal scrolls, the math still holds.
- **History invalidation** (`jsonmessage.go:277`): the moment a message arrives
  with *no* ID (a plain log line), the whole `ids` map is reset. Otherwise a stale
  offset would point at a line that has since scrolled away and it would corrupt
  unrelated output. This is the one sharp edge of the relative-offset trick.
- **No ticker.** Painting is purely event-driven; the daemon's message rate is the
  frame rate. Spinners/animation are not this system's job.
- **Does not clamp to screen height.** It assumes the layer count fits the
  viewport. If more layers than rows are active, the cursor-up offsets exceed the
  visible area and rendering degrades — the known limitation of this simpler model,
  and exactly the problem Strategy B solves.

---

## 4. Strategy B — fixed-block full repaint (`docker compose up`)

**File:** `compose/cmd/display/tty.go` — type `ttyWriter`. This is the one worth
studying most closely for a Java port; it's a complete, self-contained live UI.

### Shape

`ttyWriter` owns a rectangular block of the terminal: a header line + one line per
top-level task + an optional "… N more" line. It repaints the *entire* block on a
fixed cadence. Contrast with Strategy A: simpler to reason about (no per-line
bookkeeping), at the cost of rewriting everything each frame — which is fine
because the write is batched and the block is clamped to the viewport.

### The repaint loop

`Start()` (`tty.go:158`) launches a goroutine with a **100ms ticker** (10 fps):

```go
w.ticker = time.NewTicker(100 * time.Millisecond)
// on each tick: w.print()
```

Events (`On`/`event`, `tty.go:188`) do **not** paint directly while the ticker is
running — they only mutate the task model under a mutex. The ticker is the single
painter. (Producers and the painter are decoupled; the model is the shared state.)

### One frame (`printWithDimensions`, `tty.go:296`)

The whole frame is one logical operation under a mutex. Sequence:

1. **Jump to top of the block and hide the cursor**, batched into a single write
   via an `aec` builder so it emits as one syscall:
   ```go
   up := w.numLines + 1                    // rows printed last frame, + header
   if !w.repeated { up--; w.repeated = true }  // first frame: nothing above yet
   b := aec.NewBuilder(aec.Hide, aec.Up(up), aec.Column(0))
   fmt.Fprint(w.out, b.ANSI)
   defer fmt.Fprint(w.out, aec.Show)       // cursor shown again after the frame
   ```
   `w.numLines` is how many lines the *previous* frame emitted — that's how it
   knows how far up to go. This is the crux of the fixed-block model.
   **First-paint off-by-one (a Java landmine):** the `repeated` flag makes the
   very first frame move up `0` lines (`up = numLines(0) + 1`, then `up--`), because
   nothing has been printed above the cursor yet; every subsequent frame moves up
   `numLines + 1` (the task rows *plus* the header row). A port that unconditionally
   moves `numLines + 1` will, on frame one, rewind over — and then overwrite — the
   line that was on screen *before* the block started. Easy to reproduce, easy to
   miss; the guard must be a one-shot latch, not a count.
2. **Print header**: `[+] <operation> <done>/<total>`.
3. **Compute the visible task set** (see overflow handling below).
4. **Build each line as pure data first** (`lineData`, `tty.go:270`), then render.
   Formatting (truncation, padding, alignment) is computed before any byte is
   written — a clean separation that ports directly to Java.
5. **Overwrite each line fully.** Every line is padded with trailing spaces out to
   the terminal width, so leftover characters from a previously-longer line are
   painted over rather than erased. It rewrites rather than clears.
6. **Shrink handling** (`tty.go:380`): if this frame has fewer lines than last
   frame, it prints full-width blank lines to wipe the orphaned rows:
   ```go
   for i := numLines; i < w.numLines; i++ {
       fmt.Fprintln(w.out, strings.Repeat(" ", terminalWidth))
   }
   ```
7. Store `w.numLines = numLines` for the next frame's "up" calculation.

### Why it doesn't flicker

- **Cursor hidden for the whole frame** (`aec.Hide` … deferred `aec.Show`) — the
  cursor never visibly races across the block being redrawn.
- **No clear-then-write.** It overwrites in place with padded, full-width lines.
  Clearing first would produce a visible blank flash at 10fps; overwriting doesn't.
- **The cursor-up move is batched** with hide and column-reset into one write, so a
  frame starts atomically.
- **Timers are left-padded to a common width** (`tty.go:344`) so the right edge
  stays fixed and a shrinking value (`10.6s` → `0.0s`) can't leave a stale `s`
  hanging. Alignment is treated as a correctness issue, not cosmetics.

### Overflow: more tasks than screen rows

This is the problem Strategy A ignores, and the reason the fixed-block model needs
the viewport height. `printWithDimensions` (`tty.go:324`):

```go
maxLines := max(terminalHeight-2, 1)      // reserve header + "more" line
showMore := len(allTasks) > maxLines
if showMore {
    tasksToShow = allTasks[:maxLines-1]    // last visible row is the summary
}
// ... after rendering:
if showMore {
    fmt.Fprintf(w.out, " ... %d more", len(allTasks)-len(tasksToShow))
}
```

The block is **hard-clamped to the viewport**. It must be — if the block were
taller than the screen, `aec.Up(numLines)` would try to move above the top of the
scroll region, the terminal would clamp it, and every subsequent frame's line math
would drift. Clamping keeps the cursor-up accounting valid. Child tasks (e.g. the
layers of an image) are *not* given their own rows at all: they're aggregated into
the parent's progress bar (`prepareLineData`/`childrenTasks`, `tty.go:548`), which
keeps the row count proportional to top-level tasks, not total work items.

### Overflow: line wider than the terminal

Equally important — a wrapped line would occupy two physical rows and desync the
line count. `adjustLineWidth` (`tty.go:415`) iteratively truncates until every line
fits the width, in priority order:

1. drop trailing `details`,
2. drop the `X MB / Y MB` size suffix,
3. truncate the longest task ID (down to a 10-char floor, appending `...`).

Capped at 100 iterations as a safety valve. Truncation is rune-aware to avoid
emitting broken UTF-8.

### Coexisting with another renderer (BuildKit)

When a build starts, compose's own ticker would fight BuildKit's progress display.
`event()` (`tty.go:206`) **suspends its own ticker** on `StatusBuilding` and
resumes afterward — a clean protocol for handing the terminal to a nested renderer
and taking it back. Worth stealing for any library that must compose with others.

---

## 5. Strategy C — bottom overlay + interactive input

**File:** `compose/cmd/formatter/shortcut.go` — type `LogKeyboard`.

While `docker compose up` streams container logs, a persistent menu sits at the
bottom (`w Watch   d Detach   v View in Docker Desktop …`). This is what makes it
feel like an application rather than a log dump. Two mechanisms:

### Anchored bottom region via save/restore

Instead of tracking offsets, it uses **absolute positioning bracketed by
save/restore** so the log stream above is never disturbed
(`printNavigationMenu`, `shortcut.go`):

```go
height := goterm.Height()
carriageReturn()
saveCursor()                                  // \x1b[s — remember where logs are
moveCursor(height-extraLines(menu), 0)        // jump to the bottom rows
clearLine()
fmt.Print(menu)
restoreCursor()                               // \x1b[u — back to the log cursor
```

`clearNavigationMenu()` wipes the region (loop of `moveCursorDown`+`clearLine`)
before the next log line is flushed, so the menu is torn down and rebuilt around
each burst of log output. The log consumer is wrapped in a decorator
(`Decorate`/`logDecorator`, `shortcut.go`) whose `Before` clears the menu and
`After` reprints it — so the overlay is re-applied after every log write without
the log-producing code knowing the overlay exists.

`extraLines()` (`shortcut.go`) computes how many physical rows a string occupies:
`floor(visibleLen / terminalWidth)` — used to keep the anchor correct when the
menu (or an error message) is wide enough to wrap.

### Raw keyboard input

`HandleKeyEvents` (`shortcut.go`) reads keys via `github.com/eiannone/keyboard`
and dispatches: `d` detach, `w` toggle watch, `v/o/l` open Docker Desktop views,
`Ctrl-C` → show cursor, tear down menu, send `SIGINT` to the main thread.
Complementing this, `cli/streams` (`stream.go` `setRawTerminal`) puts the terminal
in raw mode via `moby/term` so keystrokes arrive unbuffered and un-echoed — the
precondition for any single-keypress UI. Note the `NORAW` env-var escape hatch.

---

## 6. Cross-cutting: your specific questions answered

| Question | Strategy A (jsonmessage) | Strategy B (compose ttyWriter) | Strategy C (overlay) |
|---|---|---|---|
| **Paint scope** | single dirty line | whole block every frame | one anchored region |
| **Repaint trigger** | each incoming message (event-driven) | 100ms ticker, 10fps | each log flush + key event |
| **Window size: how** | `term.GetWinsize(fd)` (moby/term → `TIOCGWINSZ` ioctl) | `goterm.Width()`/`Height()` | `goterm.Height()`/`Width()` |
| **Window size: when** | once at stream start | **every frame** (re-probed each tick) | every menu paint |
| **Size fallback** | width 200 | 80×24 (`tty.go:287`) | — |
| **Reacts to live resize** | no (cached) | yes (re-reads each tick) | yes |
| **Cursor hide/show** | not hidden (single-line writes are fast) | `aec.Hide` for the frame, `aec.Show` deferred | `showCursor()` on exit; save/restore around draws |
| **Height overflow** | unhandled (assumes it fits) | clamp to `height-2`, "… N more", children aggregated | menu pinned to bottom row |
| **Width overflow** | progress bar suppressed under 110 cols | iterative truncation (details→size→ID) | `extraLines()` accounts for wrap |
| **Anti-flicker** | only touches changed line | hide cursor + overwrite (no clear) + batched moves + padded widths | save/restore keeps log cursor stable |
| **Output stream** | stdout | **stderr** (so `up \| tee` doesn't corrupt UI) | stderr |
| **Terminal detection** | `term.GetFdInfo(out)` | `dockerCli.Err().IsTerminal()` | same |

### How terminal size is actually obtained

The bottom of the stack is `cli/cli/streams/stream.go`:

```go
func (s *commonStream) terminalSize() (height, width uint) {
    if !s.tty { return 0, 0 }
    ws, _ := term.GetWinsize(s.fd)     // moby/term → TIOCGWINSZ ioctl on the fd
    return uint(ws.Height), uint(ws.Width)
}
```

`term.GetFdInfo` also tells you whether the stream *is* a TTY (via `isatty` on the
fd). That single boolean gates the whole thing — see the next section.

That is the CLI's path. **Compose's fixed-block renderer uses a different, less
careful one:** it calls `goterm.Width()`/`Height()` (`tty.go:285-286`), which under
the hood is `getWinsize()` → `unix.IoctlGetWinsize(int(os.Stdout.Fd()), TIOCGWINSZ)`
(`reference-src/goterm/terminal_sysioctl.go:16`). Two consequences worth knowing:

- **Good:** no fd is cached — the ioctl runs on every call, so re-reading each 100ms
  tick genuinely reflects a live resize (this is why "reacts to live resize" is
  *yes* for Strategy B above, with no `SIGWINCH` handler needed).
- **Bad — a real latent bug:** the fd is **hardcoded to stdout**, but compose writes
  its UI to **stderr** (`Err()`). So with `docker compose up >file` (stdout
  redirected, stderr still a TTY), the size ioctl targets the redirected, non-tty
  stdout, fails, and returns `-1`; compose then falls back to its 80×24 default
  (`tty.go:287-292`) and renders the whole UI at 80 columns even though the real
  terminal (stderr) is wider. The renderer probes a different fd than the one it
  paints to. **Lesson for the Java port:** the size query and the output stream must
  reference the *same* fd — measure the descriptor you're actually drawing on.

---

## 7. Display-width correctness — a latent bug and a Java landmine

Every layout decision in the live renderers (padding, alignment, truncation,
"does this line fit the width?") depends on knowing a string's **display width** —
how many terminal *cells* it occupies. That is not its byte length, and not its
codepoint/rune count: a CJK ideograph is one rune but **two cells**; combining
marks and zero-width joiners are runes but **zero cells**; many emoji are multiple
runes rendering as two cells. Get this wrong and columns drift, truncation cuts in
the wrong place, and the fixed-block line math desyncs.

The Docker codebases handle this **inconsistently**, and the live renderer is on
the wrong side:

- **The correct primitive exists** — `cli/internal/tui/count.go`:
  ```go
  func Width(s string) int { return runewidth.StringWidth(cleanANSI(s)) }
  ```
  It strips ANSI first, then measures true cell width via
  `github.com/mattn/go-runewidth`, and its `Ellipsis()` truncates by that width.
  The table formatter is width-aware too (`cli/cli/command/formatter/displayutils.go`
  uses `golang.org/x/text/width` for East-Asian width; `tabwriter` uses runewidth).
  But `internal/tui` is a **static** styling helper (chips, notes, colored lines) —
  *not* wired into either live renderer.
- **The live renderers measure by rune count**, not cell width:
  - Compose `tty.go` uses `utf8.RuneCountInString(...)` throughout its padding and
    alignment (`tty.go:349`, `392`, `400`, `405`, `530`) and a hand-rolled
    `lenAnsi()` (`tty.go:677`) that counts *runes* after skipping escape sequences.
    Both over-count wide glyphs' neighbors and under-count nothing — so a task ID or
    status containing CJK/emoji throws off column alignment and the fit-to-width
    truncation in `adjustLineWidth`.
  - Compose's *menu* renderer (Strategy C) is worse still: `formatter/ansi.go:94`
    defines a **second, different** `lenAnsi()` — `len(stripansi.Strip(s))` — that
    measures **bytes**, not runes and not cells. It feeds `extraLines()`
    (`shortcut.go:379`: `floor(lenAnsi(s) / goterm.Width())`), the wrapped-line count
    used to absolutely-position the bottom menu and error rows (`shortcut.go:54,160`).
    Any multibyte glyph over-counts — even the `→` in an error prefix
    (`addError`, 3 bytes / 1 cell) — so a non-ASCII menu or error line can misplace
    the pinned menu by a row. Two live-render paths, two *different* wrong width
    measures (runes in B, bytes in C), neither the cell-width primitive that ships
    in the same tree.
  - jsonmessage `Display` doesn't measure text width at all (it relies on `\r` +
    full-line erase), so it sidesteps the issue — but it also can't align columns.

Net: the fancy live UI (`compose up`) has a latent wide-character alignment bug,
and the codebase already ships the function that would fix it. **Don't inherit the
bug.** For the Java port this is the single sharpest trap, because Java makes it
*easier* to get wrong:

- `String.length()` returns **UTF-16 code units** — an astral-plane emoji is length
  2 before you even consider cell width.
- `String.codePointCount(...)` gets you runes, still not cells.
- You need a **wcwidth-equivalent**: a lookup over Unicode East-Asian-Width +
  zero-width/combining ranges (the data behind `go-runewidth` / `wcwidth(3)`),
  applied *after* stripping ANSI SGR/OSC sequences. Build one width function, route
  all padding/alignment/truncation through it, and unit-test it with CJK, a
  combining-accent string, and a ZWJ emoji (👨‍👩‍👧 = many runes, 2 cells).

---

## 8. The abstraction seam (the part to copy for Java)

The cleanest idea in the codebase, and the one to base a Java API on, is that
producers never touch the terminal. They emit **events** to an interface, and a
renderer chosen at startup owns the screen.

**Interface** (`compose/pkg/api/event.go:99`):

```go
type EventProcessor interface {
    Start(ctx context.Context, operation string)  // begin an operation
    On(events ...Resource)                         // report task state/progress
    Done(operation string, success bool)           // operation finished
}
```

**Event** (`event.go:74`) — a flat, renderer-agnostic value:

```go
type Resource struct {
    ID, ParentID  string        // ParentID lets children roll up into a parent bar
    Text, Details string
    Status        EventStatus   // Working | Done | Warning | Error
    Current, Total int64        // progress
    Percent       int
}
```

**Implementations** are swappable, selected by capability
(`selectEventProcessor`, `compose/cmd/compose/compose.go:647`):

- `display.Full` → `ttyWriter` (Strategy B) — chosen when `Err().IsTerminal()`.
- `display.Plain` → `plainWriter` — dumps `id text details\n`, no cursor motion;
  chosen when not a TTY or `--ansi=never` / output piped.
- `display.Quiet` → discards.
- `display.Json` → machine-readable stream.

The selection logic is the important discipline: **probe the actual output fd for
TTY-ness and downgrade gracefully** to a write-once mode when it isn't one (CI
logs, pipes, `2>file`). The same event stream drives all four. Note it probes
`Err()` (stderr), because that's where the UI writes — probing stdout would force
plain mode whenever stdout alone is redirected.

Supporting model pieces worth noting for a port:

- **`task`** (`tty.go:67`) — the per-row model the renderer mutates from events;
  the render function is pure over the task set.
- **`Spinner`** (`spinner.go`) — frame advance is driven by **elapsed wall-clock
  time** (`time.Since > 100ms`), *not* coupled to the paint tick. So spinner speed
  is independent of frame rate, and a Windows fallback swaps the braille frames for
  `-`.
- **Insertion-ordered IDs** — a `[]string ids` slice alongside the `map`, so rows
  render in first-seen order and don't jump around between frames (stability =
  perceived smoothness).

---

## 9. Essential classes / files index

| File | Role |
|---|---|
| `cli/.../jsonmessage/jsonmessage.go` | Strategy A: sparse dirty-line layered progress; the `ids` offset map |
| `cli/cli/streams/stream.go` | `GetWinsize`, `IsTerminal`, raw-mode — the syscall floor |
| `cli/cli/streams/out.go` | `Out` stream: `IsTerminal`, `GetTtySize`, `SetRawTerminal` |
| `compose/pkg/api/event.go` | `EventProcessor` interface + `Resource` event — the seam |
| `compose/cmd/display/tty.go` | Strategy B: `ttyWriter`, the full-repaint loop, overflow + truncation |
| `compose/cmd/display/spinner.go` | time-based spinner, OS fallback |
| `compose/cmd/display/plain.go` / `mode.go` | non-TTY fallback + mode enum |
| `compose/cmd/compose/compose.go:647` | `selectEventProcessor` — capability-based renderer choice |
| `compose/cmd/formatter/ansi.go` | thin ANSI helpers (cursor save/restore/move, clearLine, OSC8) |
| `compose/cmd/formatter/shortcut.go` | Strategy C: bottom-menu overlay + raw keyboard input |
| `cli/internal/tui/count.go` | **correct** display-width (`runewidth` + ANSI strip) + `Ellipsis` — exists but NOT used by the live renderers (see §7) |

---

## 10. Notes for the Java port

Direct mappings, and the traps:

- **Escape codes are just strings.** Write them to whatever your `Appendable`/
  `PrintStream` is. No JNI needed for output.
- **Size + TTY detection is the one native gap.** Java has no built-in
  `TIOCGWINSZ` or `isatty`. Options, in order of preference:
  - `System.console() != null` as a coarse isatty proxy (but it's null under
    Gradle/IDE even on a real terminal — unreliable).
  - Parse `$COLUMNS`/`$LINES` or shell out to `stty size` / `tput cols` once at
    start (cheap, portable-ish on POSIX).
  - JNA/JNR-FFI `ioctl(TIOCGWINSZ)` for live resize, or read `SIGWINCH`.
  - On JDK 22+, the FFM API (`java.lang.foreign`) can call `ioctl` with no
    third-party dependency — probably the cleanest modern answer.
- **Pick your strategy per use case, don't build all three.** For a bounded task
  list, Strategy B (fixed block, ticker, full repaint) is the simplest correct
  design and the easiest to make flicker-free. Only reach for Strategy A's
  per-line offset map if row count is unbounded/streaming.
- **Replicate the non-TTY downgrade from day one.** Gate all cursor motion behind
  one `isTerminal` boolean; when false, emit plain append-only lines. This is what
  keeps output sane in CI and pipes, and it's trivial to add up front and painful
  to retrofit.
- **Steal the anti-flicker checklist:** hide cursor for the frame; overwrite with
  width-padded lines instead of clear-then-write; batch the cursor-up + hide into
  one write; clamp the block to viewport height; keep row order stable; decouple
  spinner timing from frame timing.
- **Concurrency:** one painter (the ticker thread) owns the screen; producers only
  mutate the model under a lock. Don't let N threads write escape codes. In Java:
  a single scheduled executor for the paint tick, a `ReentrantLock` (or a
  concurrent model) for state, and treat the terminal as owned by the painter.
- **Threading the render as pure function** (compute `lineData` list, then emit)
  makes it unit-testable — compose captures rendered frames as golden strings in
  `tty_test.go`. Do the same: render to a `String`/buffer, assert on it, then flush.

---

## 11. Reusable prompt for analyzing other codebases

The prompt for running this analysis on other repos now lives in its own file —
**`terminal-rendering-analysis-prompt.md`** — as a merged, two-part instrument
(Part 1 analysis for Opus, Part 2 review for Fable). It supersedes the earlier
single-pass draft that used to live here: it adds coverage of terminal primitives
this codebase happens not to use (alternate screen buffer, `?2026` synchronized
update, scroll regions, SIGWINCH, crash-restore), plus the two questions this
analysis proved matter most for a *library* — the producer↔renderer **seam** and
**display-width correctness** (§7).

Cross-codebase results accumulate in **`SURVEY.md`**; the Fable review of *this*
report is briefed in **`FABLE-REVIEW-BRIEF.md`**.
