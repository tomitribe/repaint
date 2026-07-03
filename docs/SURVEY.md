# Live-terminal rendering survey — cross-codebase index

Purpose: reverse-engineer how multiple CLIs do live in-place terminal rendering, to
inform a clean-room **Java** library. Each repo is analyzed with the two-part prompt
in `terminal-rendering-analysis-prompt.md` (Opus analyzes, Fable reviews). Each
analysis emits a **summary block**; those blocks accumulate into the matrix below so
patterns become visible across implementations.

## Process (per codebase)

1. Open a Claude session in the repo; run **Part 1** of the prompt (Opus).
2. Switch to **Fable**; run **Part 2** against the Part-1 output.
3. Save the reviewed report as `<repo>-live-terminal-rendering.md`.
4. Paste its summary block into the matrix here and note anything worth stealing.

## Reports

| Repo | Report | Status |
|---|---|---|
| docker/cli + docker/compose | `docker-live-terminal-rendering.md` | analyzed (Opus); **reviewed (Fable)** — corrections in `docker-live-terminal-rendering.REVIEW.md`, applied below |
| jline3 (@ d861ac72, ~v4.3.1) | `jline3-live-terminal-rendering.md` | analyzed (Opus agent); **reviewed (Fable)** — corrections in `jline3-live-terminal-rendering.REVIEW.md`, applied below |
| bubbletea (v2.0.8 @ fc707bb + v1.3.9 worktree + charmbracelet-x + ultraviolet @ pinned f5a850f) | `bubbletea-live-terminal-rendering.md` | analyzed (Opus agent); **reviewed (Fable)** — all `[inferred: uv]` claims verified against pinned ultraviolet source; corrections in `bubbletea-live-terminal-rendering.REVIEW.md`, applied below |
| _(optional: buildkit `progressui`)_ | — | demoted — same Docker-family technique, low new information |

Survey coverage is now sufficient to start the Java spike: four painting
models, three native-layer strategies, and all original open questions
answered or reduced to spike-time decisions.

## Comparison matrix

Filled from each report's summary block. `n/a` = the codebase doesn't do it.

| Dimension | docker (cli jsonmessage) | docker (compose ttyWriter) | jline3 | bubbletea (v2; v1 delta noted) |
|---|---|---|---|---|
| painting-model | dirty-diff (per-line) | floating-block (full repaint) | dirty-diff (per-line prefix/suffix + intra-line skips; `dl`/`il` scroll opt **fullscreen-or-viewport-filling only**, `Display.java:475-478`) | **cell-grid diff** (ncurses lineage, ultraviolet); cell = **grapheme-cluster string** + style + link + width, wide continuation = zero-width cell (`uv/cell.go:15-29,69`); v1 was line-diff |
| cursor-motion | relative + `\r` | relative (`aec.Up`) | mixed — relative w/ cost-chosen forms; absolute `cup` only fullscreen diagonal | mixed, **length-minimized** — emits shortest of CUP / relative / CR+relative / home+relative (`uv/terminal_renderer.go:388`) |
| repaint | event-driven | timer 100ms (10fps) | event-driven (per key binding; no timer, no debounce) | **hybrid**: `View()` at message rate, paint at fps ticker (60 default / 120 cap, `renderer.go:10-15`); ticker is the sole writer (`tea.go:1408-1421`) |
| size-source / resize | `GetWinsize` ioctl / cached-once | `goterm` ioctl / per-frame | FFM `ioctl` / `stty -a` exec / Win console-api; **SIGWINCH-driven** (Win: `WINDOW_BUFFER_SIZE_EVENT`→WINCH); env `COLUMNS`/`LINES` unused | ioctl `TIOCGWINSZ` (`term/term_unix.go:64-70`) / SIGWINCH → `WindowSizeMsg` → erase+full redraw; Windows via ConPTY input events |
| vertical-overflow | none (assumes fits) | cap + "N more" + aggregate children | **silent** clamp to rows (no indicator, `Display.java:533`); `Status` pins via scroll-region | drop lines off the **top** (`cursed_renderer.go:315-317`; v1 same); alt-screen (`?1049`) as opt-in mode |
| width-measure / wide-char-correct | none — relies on `\r` + full-line erase / n/a | **mixed bytes+runes** in one layout engine (block, `tty.go:338` vs `:349`) + bytes (menu, `ansi.go:94`); details truncation byte-slices → can emit invalid UTF-8 (`tty.go:513`) / **no (cell-width `internal/tui/count.go` is module-internal to cli — compose *cannot* import it; its `Ellipsis` is rune-based anyway)** | wcwidth (Kuhn port, Unicode 16 tables) / **partial, probe-gated**: per-cluster only when mode 2027 or the emoji-CPR probe proves the terminal groups; per-codepoint otherwise (`WCWidth.java:683-687`) | grapheme (uniseg) after a successful **2027 probe**; default is WcWidth — ZWJ emoji = **1** until upgraded (`ansi/method_test.go:22-23`) / partial by default, yes after probe. On upgrade it also flips the *terminal* into 2027 (`cursed_renderer.go:689-704`) |
| flicker-controls | touch-only-changed-line | hide-cursor, overwrite+pad, batch-move, timer-left-pad, stable-order | minimal-diff, `?2026` sync (fullscreen, emitted blind, END in `finally`), whole-frame byte buffer + single flush, style-state carry, cost-based capability choice | framerate coalescing, cell diff, `?2026` (probed), hide-cursor wrap, buffered single `io.Copy` flush, line-hash scroll-optimize (fullscreen-only; off on Windows — Windows Terminal DECSTBM bug), 3-level skip-if-unchanged |
| synchronized-update-2026 | no | no | **yes** (fullscreen frames; unsupporting terminals ignore it; probe exists but unused — emit-blind is the field-proven pattern) | **yes, probed** (DECRQM behind an env allow-list excluding **Apple Terminal + SSH** — same scar as JLine's blacklist, `tea.go:968-987`); enabled only on `ModeReset` report; v1 had none |
| alt-screen | no | no | no (`smcup`/`rmcup` shipped as caps, never emitted by `Display`) | yes — `?1049`, enter/exit ordering matters (save-cursor→set→erase / erase→reset→restore, `cursed_renderer.go:645-671`) |
| pinned-lines | n/a | save-restore + absolute (compose menu) — menu+logs on **stdout**, progress block on **stderr** | **scroll-region (DECSTBM)** + save/restore-cursor decorator (`Status`); region re-established on resize (terminals reset it) | **insertAbove**: scroll up, write log lines, reset diff origin → UI repaints below (`cursed_renderer.go:707-763`); v1's public DECSTBM API deprecated & removed in v2 |
| input | n/a | raw-keyboard (`eiannone/keyboard`): opens `/dev/tty` directly, own termios with **ISIG cleared**, SIGIO async reads, manual SIGINT re-synthesis on Ctrl-C | raw termios (ISIG cleared too) + `NonBlockingReader` pump thread + `KeyMap` trie + **1000ms ambiguity timeout** for lone-ESC vs escape-sequence | raw-keyboard; **cancelreader** (self-pipe + poll makes the blocking read itself cancelable, + 500ms "cancel may have failed" wait `tty.go:97-105`); kitty keyboard, SGR mouse, bracketed paste — all probed |
| non-tty-fallback | non-TTY drops progress messages entirely (`jsonmessage.go:141-143`); the <110-col bar gate is a separate TTY-mode width check | plain / quiet / json | `DumbTerminal` (`dumb`/`dumb-color`), ANSI stripped, size `(0,0)` → 1×unbounded; **no `NO_COLOR`/`CLICOLOR`** | keeps running: opens `/dev/tty` when stdin is piped (`tea.go:1008-1017`); `nilRenderer` for daemon/plain mode; color downsampled per profile |
| seam / renderer-select | none (inline) | `EventProcessor` iface / block by `Err().IsTerminal()`; menu by `Out()`+`In()` TTY + `COMPOSE_MENU`; `disableAnsi` from an `Out()` probe — **three independent gates** | `TerminalProvider` SPI / ordered `"ffm,jni,exec"`, first that yields a TTY wins, else dumb | `Model{Init,Update,View}` + `renderer` iface; **declarative `View` struct** (modes as diffable fields, not imperative commands — the v1→v2 verdict); nil-vs-cursed renderer, injectable |
| concurrency | single caller | single painter goroutine + mutex-guarded model | single painter + `ReentrantLock`; `Display` explicitly not thread-safe; signal handler = one atomic store + dispatcher thread | one **unbuffered** msgs chan as serialization point (`tea.go:598`); goroutines: input reader, signal, SIGWINCH, cmd-runners, fps ticker (**sole terminal writer**), event loop (sole consumer) |
| jvm-hard-parts | ioctl, isatty | ioctl, isatty, raw-mode (`/dev/tty` access), wcwidth, SIGWINCH, Windows `SetConsoleMode` VT enablement | **all SOLVED** — FFM: `isatty`, `ioctl(TIOCGWINSZ)`, `tcget/tcsetattr`, `sigaction`+upcall (4 downcalls, 2 structs); exec `stty`/`tty` fallback; Win FFM console API + VTP | beyond JLine's set: cancelable blocking stdin read (Java: NIO `Selector` over a pipe, or FFM `poll` + self-pipe wake fd); ConPTY resize events; grapheme segmenter matching the terminal (2027 negotiation) |

## Running design decisions for the Java library

Accumulating conclusions as evidence comes in. Each should trace to ≥1 repo.

- **Split the seam first.** Producer emits events to an interface; a renderer chosen
  at startup owns the screen. Public API = that interface. _(docker/compose)_
- **Two renderers minimum from day one:** a TTY renderer and a plain append-only
  one, selected by an `isTerminal` probe on the **output** fd. _(docker/compose)_
- **Measure size on the same fd you draw on — and always have a sane fallback.**
  Compose's *block* queries size via `goterm`, which ioctls a hardcoded
  `os.Stdout.Fd()`, while it paints to stderr — so `compose up >file` collapses the
  UI to the 80×24 fallback on a wide terminal. The *menu* is fd-consistent (stdout)
  but has **no fallback at all**: it divides by a possibly `-1` width unguarded and
  `uint`-casts negative rows. The rule is both halves: probe, size query, and paint
  target on one descriptor, **and** clamp failure sentinels to a default.
  _(two bugs found in docker/compose — Fable review)_
- **For a bounded task list, prefer the floating-block full-repaint model** (ticker
  + full frame) over per-line diffing — simpler to keep correct and flicker-free;
  clamp the block to the viewport. _(docker/compose)_
- **Measure display width with a wcwidth table, not `String.length()`** — both
  Docker renderers get this wrong; don't inherit the bug. _(gap found in docker)_
- **Adopt `?2026` synchronized-update, emitted blind.** JLine wraps every
  fullscreen frame in `\033[?2026h…l` without probing — unsupporting terminals
  ignore it — and puts the END in a `finally` so sync mode can't leak on
  exception (`Display.java:126-127,452-454,761-767`). Cheap, modern, and
  field-proven; combine with (not instead of) hide-cursor for inline frames.
  _(jline3)_
- **Probe the terminal, don't guess — the answer to the width problem.** JLine
  interrogates the terminal at startup: one batch write of `CSI ? u` + DECRQM
  queries (`?2026$p ?2027$p ?2048$p`) + a DA1 sentinel (`CSI c`) whose
  near-universal response fences the read (`AbstractTerminal.java:507-540`);
  and when mode 2027 is unsupported, an **emoji cursor-displacement probe** —
  write 🇫🇷 and 👩‍🔬, issue DSR/CPR, check the cursor advanced exactly 2 columns
  (`AbstractTerminal.java:763-802`). Width measurement is then gated on what
  the terminal *proved* it renders (`WCWidth.java:683-687`), with a
  full-line-repaint fallback in cluster mode because clusters merge
  retroactively (`Display.java:585-609`). This dissolves the
  "terminal disagrees with your wcwidth table" trap the docker review could
  only warn about. Caveats to copy: probe timeout + input drain, and a
  `TERM_PROGRAM=Apple_Terminal` blacklist (its parser leaks the probe as
  visible text, `AbstractTerminal.java:477-486`). _(jline3)_
- **Native layer decision: FFM-only + `stty` exec fallback.** The entire POSIX
  surface is 4 downcalls + 2 structs — `isatty(int)`, `ioctl(TIOCGWINSZ)`
  (+variadic hint), `tcgetattr`/`tcsetattr` (per-OS `termios` layout),
  `sigaction(SIGWINCH)` with an upcall stub that does one atomic store and a
  1ms-parking dispatcher thread (`CLibrary.java`, `FfmSignalHandler.java` are
  near-complete blueprints, incl. per-OS `TIOCGWINSZ` constants at
  `CLibrary.java:499-530`). Fallback: exec `tty` / `stty -a` / `stty <flags>`
  with regex parsing (`ExecPty.java`) — no SIGWINCH there, so poll. Windows:
  FFM console API (`GetStdHandle`/`Get|SetConsoleMode`/
  `GetConsoleScreenBufferInfo`/`ReadConsoleInputW`), VTP bit, resize arrives
  as `WINDOW_BUFFER_SIZE_EVENT` → synthesize WINCH. Skip JNI and
  `sun.misc.Signal` entirely. _(jline3 — settles the jvm-hard-parts row)_
- **Skip terminfo; hardcode the ~15 ANSI sequences.** JLine's `.caps` +
  `Curses.tputs` interpreter is the price of supporting exotic terminals; a
  bare-bones lib targeting VT/ANSI doesn't need it. If ever adopted, never
  copy the `$<delay>` handling — it `Thread.sleep`s on the render path under a
  comment claiming it doesn't (`Curses.java:466,480-486`). _(jline3)_
- **Model buffer width separately from window width.** Windows consoles can
  have a buffer wider than the visible window; auto-wrap happens at buffer
  width, so wrap-reliance must be disabled when `bufferWidth > columns`
  (`Display.java:266-273`). A single "width" field mis-wraps there. _(jline3)_
- **Coalesce with store-latest + a paint ticker.** Producers run at message
  rate and only overwrite a stored frame; a single fps ticker (60/120 cap)
  flushes the *latest* frame — intermediate frames dropped, never queued; the
  producer is backpressured by the event loop (one unbuffered channel), never
  by the terminal. Add skip-if-unchanged so a steady view costs ~nothing per
  tick (`tea.go:872-880,1408-1421`, `cursed_renderer.go:287-290`). This
  answers the fast-producer question the docker/JLine models couldn't.
  _(bubbletea)_
- **Make terminal state declarative and diff it.** The v1→v2 verdict: modes
  (alt-screen, mouse, cursor visibility, title, …) moved from imperative
  commands into fields of a `View` value object, diffed per frame with only
  deltas emitted (`tea.go:84-190`, `cursed_renderer.go:320-458`). One code
  path keeps the terminal consistent; no mode drift. For the Java API: the
  renderer takes a frame *description*, not a command sequence. _(bubbletea)_
- **Cell content = one grapheme cluster as a `String`.** ultraviolet evolved
  from cellbuf's `{rune + combining[]}` to `{Content string}` per cell, wide
  continuation = zero-width cell (`uv/cell.go:15-29,69`). For Java this
  sidesteps the char/codepoint mismatch entirely — never model a cell as a
  `char` or code point. _(bubbletea/uv)_
- **Interleave logs with `insertAbove`, not a scroll-region API.** v1 shipped
  public DECSTBM scroll APIs and deprecated them all; v2's replacement:
  scroll the screen up, write the permanent lines, reset the diff origin so
  the live UI repaints below (`cursed_renderer.go:707-763`). Keep DECSTBM
  internal (JLine `Status`-style pinning) — don't expose it. _(bubbletea)_
- **Probe blacklists are convergent evidence.** JLine blacklists
  `TERM_PROGRAM=Apple_Terminal` for DECRQM; bubbletea's allow-list excludes
  Apple Terminal *and SSH sessions* (`tea.go:968-987`). Bake both exclusions
  into the probe design from day one. _(jline3 + bubbletea)_
- **Decide the `OPOST` question consciously.** bubbletea's raw mode is full
  `cfmakeraw` (clears `OPOST` → you own `\n`→`\r\n` mapping,
  `term/term_unix.go:31`); JLine leaves output flags alone. For an inline
  renderer, JLine's choice is simpler; full raw is only needed if you own the
  whole screen. _(bubbletea vs jline3)_
- **Anti-flicker baseline:** hide cursor per frame, overwrite width-padded lines
  (never clear-then-write), batch the cursor moves into one write, blank-fill
  removed lines, keep row order stable. _(docker/compose)_
- **Decouple spinner timing from frame rate — compose fails at this.** Its spinner
  clock is never reset (`spinner.go:45,56-59`), so after 100ms it advances on every
  read: spinner speed = paint rate. Reset the timestamp on each advance.
  _(gap found in docker/compose — Fable review)_
- **Render the whole frame to a buffer, flush once.** Compose batches only the
  frame prologue (hide+up+column); the body is one unbuffered write per row.
  Buffering the frame gives atomicity *and* the golden-string testability for free.
  _(gap found in docker/compose — Fable review)_
- **Guard CSI parameter 0.** `aec.Up(0)` emitting *nothing* is load-bearing for
  compose's first-frame latch; a port emitting a literal `\x1b[0A` moves up one
  line (param 0 = default 1). Zero-count moves must emit nothing; 1-based
  column/position params must never receive 0. _(landmine found in docker — Fable review)_
- **Restore is a first-class concern:** show cursor + un-raw the terminal on normal
  exit, SIGINT/SIGTERM, and uncaught exception (try/finally + shutdown hook +
  signal handler). _(gap to confirm across repos)_

## Open questions to resolve across the survey

- ~~Does any surveyed repo use the **alternate screen buffer** or a **scroll
  region** for pinned lines?~~ **Answered (jline3):** scroll region — `Status`
  reserves bottom rows via DECSTBM with save/restore-cursor around each paint,
  and re-establishes the region on resize because terminals reset it
  (`Status.java:84-88,128-188,253-274`). Cleaner than compose's
  save/restore+absolute overlay: the terminal itself keeps scrolling output out
  of the reserved band. No surveyed repo uses alt-screen for this.
- ~~**Event coalescing** under a fast producer~~ **Answered (bubbletea):**
  store-latest + fps ticker (see design decision above). Model sees every
  message, terminal sees ≤fps frames of the latest state. One caveat worth a
  different choice in Java: bubbletea computes `View()` on *every* message
  even though most frames are dropped — gate frame *construction* on the tick
  (dirty flag) instead to avoid wasted allocation.
- ~~**SIGWINCH vs per-frame size poll**~~ **Answered (jline3):** SIGWINCH is
  cheap once FFM `sigaction` exists (one downcall + upcall stub; handler = one
  atomic store; `FfmSignalHandler.java`), and Windows synthesizes WINCH from
  `WINDOW_BUFFER_SIZE_EVENT`. Per-frame polling remains the right fallback for
  the exec path (no signal delivery there). Do both: signal-driven when native,
  poll when exec.
- ~~**Windows** minimum viable story~~ **Answered (jline3):** FFM console API —
  `GetStdHandle`, `Get/SetConsoleMode` (+`ENABLE_VIRTUAL_TERMINAL_PROCESSING`),
  `GetConsoleScreenBufferInfo` (size = `srWindow` rect, buffer size separate),
  `ReadConsoleInputW` for keys + resize events (`NativeWinSysTerminal.java`,
  `Kernel32.java`, `AbstractWindowsTerminal.java:276-294`). Requiring Windows
  Terminal shrinks but doesn't eliminate this: mode setup, size, and resize
  events still need the console API.
- **New (from jline3):** should the bare-bones lib do the emoji-CPR probe from
  day one, or ship per-codepoint wcwidth first and add probing when emoji
  alignment bites? The probe is ~40 lines but drags in DSR/CPR response
  parsing and raw-attr setup during init. _(decide at spike time)_
