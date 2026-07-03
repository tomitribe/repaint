# Fable review — `docker-live-terminal-rendering.md`

Review pass per `FABLE-REVIEW-BRIEF.md`. Every cited file was opened and checked
against source: `cli/`, `compose/`, `cli/vendor/` (aec v1.1.0, moby/term,
go-runewidth), and `reference-src/` (goterm v1.0.4, keyboard, stripansi).
Verdicts: **CONFIRMED** = claim matches source; **CORRECTION** = claim wrong or
materially incomplete, with evidence.

**Bottom line up front:** Strategies A and B's core mechanics are accurate, and
both headline findings (the goterm stdout-probe bug and the §7 width bug) are
real and confirmed. But three corrections change design conclusions: the spinner
is *not* decoupled from the paint tick (claim #3 is wrong), Strategy C writes to
**stdout** (not stderr) with entirely different gating and raw-input mechanics,
and "truncation is rune-aware" is only true for task IDs — details truncation
byte-slices and can emit invalid UTF-8.

---

## 1. Priority checklist (a)–(g)

### (a) Escape codes — CONFIRMED, with three additions and one landmine

All table entries verify:

- `\x1b[2K` — `ansiEraseLine`, `jsonmessage.go:106` (report cites :107; the
  const block is :105-109 — trivial off-by-one). Cursor up/down fmt strings
  `jsonmessage.go:107-108`.
- `aec.Hide` = `\x1b[?25l`, `aec.Show` = `\x1b[?25h` — `aec.go:136-137`.
- `aec.Save` = `\x1b[s\x1b7`, `aec.Restore` = `\x1b[u\x1b8` (SCO+DEC both) —
  `aec.go:133,135`. cli vendors aec **v1.1.0** (`cli/vendor/modules.txt`),
  identical to compose's pin (`compose/go.mod:37`) — no version skew.
- `aec.Column` = CHA `\x1b[<n>G` (`aec.go:86-88`); `aec.EraseLine(Tail)` =
  `\x1b[0K` (`aec.go:101-103`, `Tail: 0` at :127); `aec.Position` =
  `\x1b[<r>;<c>H` (`aec.go:91-93`). `\r` at `jsonmessage.go:139`.
- OSC 8 — `ansi.go:101-106`; note the actual sequence also wraps the text in
  `\x1b[4m`…`\x1b[24m` (underline), which the table omits.

**Missing from the table (in play, not listed):**

1. `\r\x1b[K` — `goterm.RESET_LINE`, prefixed to **every container status log
   line** (`compose/cmd/formatter/logs.go:134`). This is the no-param
   erase-to-tail variant and part of how logs coexist with the pinned menu.
2. SGR color/style codes, including 24-bit `38;2`/`48;2` for the menu key chips
   (`shortcut.go:382-388`) — styling, but "these are the only codes in play"
   is not literally true.
3. `\012` (`newLine()`, `ansi.go:89-91` region / `shortcut.go` usage) — trivial.

**Landmine for the Java port (not in the report):** aec special-cases `Up(0)`/
`Down(0)` to the **empty string** (`aec.go:38-51`). The first-frame `up = 0`
(claim #8) only works because of this: a port that emits a literal `\x1b[0A`
will move up **one** line, because CSI parameter 0 means "default = 1".
Conversely `aec.Column(0)` has **no** zero-guard and emits `\x1b[0G`
(`aec.go:86-88`) — compose's "carriage return" relies on terminals clamping
column 0 → 1. Same for `aec.Position(y, 0)`.

### (b) Relative vs absolute — CONFIRMED

- A: relative `cursorUp`/`cursorDown` (`jsonmessage.go:115-127`) + `\r`
  (`:139-140`); no absolute positioning anywhere in the file.
- B: relative `aec.Up(uint(up))` + `aec.Column(0)` (`tty.go:308-312`); no
  `aec.Position`.
- C: absolute `aec.Position` via `moveCursor` (`ansi.go:51-56`;
  `shortcut.go:54,160`) bracketed by save/restore — plus *relative*
  `moveCursorUp`/`moveCursorDown` for buffer allocation and the clear loop
  (`shortcut.go:143,199-201`). "Absolute" is C's anchoring mechanism; it is not
  absolute-only.

### (c) Repaint frequency / ownership — CONFIRMED

100 ms ticker at `tty.go:159`; single painter goroutine `:161-174`. `On()` only
mutates the model under `mtx` (`:188-203`); `printEvent` early-returns while
`w.operation != ""` (`:227-230`), i.e. whenever `Start()` has run and `Done()`
hasn't. A is event-driven with no ticker (whole of `jsonmessage.go`). One
caveat the report omits: `Done()` paints one final frame directly
(`tty.go:178`) — the ticker is not the *only* painter, just the only one during
the operation.

### (d) Overflow — CONFIRMED arithmetic and priority; CORRECTION on rune-safety

- `maxLines := max(terminalHeight-2, 1)` (`tty.go:325`); `showMore` slice
  `allTasks[:maxLines-1]` (`:327-331`); `... N more` line (`:371-377`);
  shrink-wipe loop (`:380-383`). All as reported.
- Priority order confirmed at `tty.go:429`:
  `truncateDetails` → `truncateProgressSize` → `truncateLongestTaskID`, inside
  `for range 100` (`:420`).
- **CORRECTION — "Truncation is rune-aware to avoid emitting broken UTF-8" is
  only one-third true.** Only `truncateLongestTaskID` is rune-safe
  (`tty.go:526-545`, slices `[]rune`). `truncateDetails` **byte-slices**:
  `l.details[:len(l.details)-reduction-3] + "..."` (`tty.go:511-513`) — on
  multi-byte details (an error message with `→`, a Unicode path) this can cut
  mid-rune and emit invalid UTF-8. `truncateProgressSize` also strips by byte
  count (`:501`), safe only because the size suffix is ASCII by construction.
- Nit: the ID floor is `minIDLen-3` = **7 runes** + `"..."` = 10 visible chars
  (`tty.go:542`); the report's "10-char floor" is right in effect.

### (e) Anti-flicker list — CONFIRMED, two caveats

- Hide for the frame + deferred Show: `tty.go:308-316`. ✔
- Overwrite-with-padding, no clear-then-write: `applyPadding` right-aligns the
  timer at exactly `terminalWidth` (`tty.go:409`), the "more" line is padded
  (`:374-375`), removed rows are blank-filled (`:380-383`). ✔
- Batched hide+up+column in one builder → one `Fprint` (`:308-313`). ✔
- Timer left-pad (`:344-356`). ✔
- Stable row order via `ids []string` (`:53`, iterated `:249-255`). ✔

Caveats a Java port should know: (1) only the frame **prologue** is one write —
the body is one `Fprint` per line to unbuffered stderr, so a frame is ~N+2
syscalls, not atomic; (2) if truncation gives up (`break` at `:431`),
`timerPad` clamps to 1 (`:409`) and an over-wide line **wraps**, silently
desyncing the `numLines` accounting the whole model depends on.

### (f) TTY detection & stream — CONFIRMED for A and B; C is wrong (see below)

- A writes progress to **stdout**: `out := dockerCLI.Out()`
  (`cli/cli/command/image/pull.go:109`) → `jsonstream.Display` →
  `jsonmessage.DisplayJSONMessagesStream(reader, stream, stream.FD(),
  stream.IsTerminal(), …)` (`cli/internal/jsonstream/display.go:64`). The
  `term.GetFdInfo(out)` path the report cites is jsonmessage's own generic
  entry (`jsonmessage.go:175,220`); the pull path gets fd/tty from
  `streams.Out`, which itself comes from `term.GetFdInfo`
  (`cli/cli/streams/stream.go:19`). Same primitive, different plumbing.
- B writes to and probes **stderr**: `dockerCli.Err().IsTerminal()` at
  `compose/cmd/compose/compose.go:654`, `display.Full(dockerCli.Err(), …)` at
  `:655`; the why-stderr comment is at `:643-646`. ✔
- Size floor: `terminalSize()` → `term.GetWinsize(s.fd)` → `TIOCGWINSZ`
  (`stream.go:65-77`). `NORAW` escape hatch at `stream.go:54`. ✔

### (g) Abstraction seam — CONFIRMED

`EventProcessor{Start, On, Done}` at `event.go:100-107`; flat `Resource` at
`event.go:75-84` with exactly the fields listed (ID, ParentID, Text, Details,
Status, Current, Percent, Total). Selection branches in `selectEventProcessor`
(`compose.go:647-681`): auto → `ansi==never` ⇒ Plain / `Err().IsTerminal()` ⇒
Full / else Plain; `tty` forced (error if `ansi==never`); `plain` (error if
`ansi==always`); `quiet`|`none` ⇒ Quiet; `json` ⇒ JSON + logrus JSON formatter.
Trivial naming nit: the constructor is `display.JSON`, not `display.Json`
(`compose.go:677`).

---

## 2. The eight flagged claims

### #1 Window-size re-read cadence — CONFIRMED

B re-probes **both** dimensions on every tick: `print()` calls
`goterm.Width()`/`Height()` (`tty.go:285-286`), and goterm performs the ioctl
on every call with no caching (`terminal.go:192-200`;
`terminal_sysioctl.go:14-22,24-36`). There is **no SIGWINCH handler anywhere in
compose** (repo-wide grep: zero hits outside vendor) — resize reactivity comes
solely from the per-tick re-read. A caches width **once** before the message
loop (`jsonmessage.go:236-243`, default 200) and never re-reads.

### #2 A does not clamp to viewport height — CONFIRMED

`jsonmessage.go` never reads the terminal height at all — the only winsize
field used is `ws.Width` (`:241`). No clamp, no scroll region, nothing. With
more live layers than rows, `cursorUp(diff)` walks off the top and rendering
degrades exactly as described.

### #3 Spinner timing decoupled from the paint tick — **CORRECTION (design-relevant)**

The mechanism is as cited — `String()` advances when
`time.Since(s.time).Milliseconds() > 100` (`spinner.go:56-59`) — but **`s.time`
is never reset**: it is set once in `NewSpinner` (`spinner.go:45`) and not
touched by `String()`, `Stop()`, or `Restart()` (`:64-70`). So after the first
100 ms of a task's life the condition is *always* true, and the spinner
advances on **every read**. Reads happen once per paint (`prepareLineData` →
`spinner(t)`, `tty.go:601,640-650`), so spinner speed is **fully coupled to the
frame rate**: the 10 fps ticker gives ~10 steps/sec; a 50 ms ticker would spin
it twice as fast. The report's conclusion — "spinner speed is independent of
frame rate" — is false; this looks like an upstream bug (missing
`s.time = time.Now()` when the index advances). The *advice* in §10 ("decouple
spinner timing from frame timing") is still right for Java — but compose is an
example of getting it wrong, not a pattern to copy.

### #4 Child tasks get no rows — CONFIRMED

Rendered rows come only from `parentTasks()` which filters
`len(t.parents) == 0` (`tty.go:247-256`, collected at `:322`). Children are
folded into the parent's completion bar and size totals in `prepareLineData`
(`tty.go:572-597`, via `childrenTasks` `:258-267`). Row count ∝ top-level
tasks. ✔

### #5 BuildKit coexistence — CONFIRMED

`event()` at `tty.go:206-215`: `StatusBuilding` → `ticker.Stop()`,
`suspended = true`; the next non-building event while suspended →
`ticker.Reset(100ms)`. While suspended nothing paints: `printEvent` no-ops
because `operation != ""` (`:227-230`), and the only out-of-band paint is
`Done()`'s final frame (`:178`) at operation end. Minor Go-semantics caveat: a
tick already queued in the channel when `Stop()` runs can deliver one last
paint — racy but harmless here.

### #6 Progress-bar width gate — CONFIRMED

`RenderTUIProgress` builds the bar only when `width > 110`
(`jsonmessage.go:60-64`); the numeric `numbersBox` is computed regardless of
width (`:66-85`, suppressed only by `HideCounts`). The ETA box has its own
`width > 50` gate (`:88-96`) the report doesn't mention. Note the interplay
with the width default: when winsize fails, width = 200 (`:237-243`; also
`Display` `:148-150`), so the bar *shows* on an unmeasurable TTY.

### #7 Display-width bug (highest stakes) — CONFIRMED, and it's worse than reported

**(i) The live renderer really measures runes, not cells — CONFIRMED, plus a
third unit.** `utf8.RuneCountInString` at `tty.go:349, 392, 400, 405, 407, 452,
491, 530-531` (the report's list plus three more sites). `display.lenAnsi`
(`tty.go:677-694`) counts runes while skipping `ESC…m`. But B doesn't use runes
consistently — it **mixes byte lengths into the same layout math**: `timerLen`
is `len()` bytes (`:338`) yet compared against a rune count (`:349`);
`maxStatusLength` is bytes (`:436-444`); `computeOverflow` measures details in
bytes (`:466`); details truncation byte-slices (`:511-513`, see (d)). Three
units — bytes, runes, cells — coexist in one layout engine, and only cells is
correct. This strengthens the report's conclusion.

**Two distinct wrong helpers — CONFIRMED.** `display.lenAnsi` counts *runes*
(`tty.go:677`); `formatter.lenAnsi` is `len(stripansi.Strip(s))` — *bytes*
(`ansi.go:94`, regex in `reference-src/stripansi/stripansi.go`). The byte one
feeds `extraLines` = `floor(lenAnsi(s)/goterm.Width())` (`shortcut.go:378-380`),
which places the error row and the pinned menu (`shortcut.go:54,160`). The `→`
in `addError` (`shortcut.go:64`) is 3 bytes / 1 cell, exactly as the report
says.

**(ii) `internal/tui` wired into neither — CONFIRMED, and stronger than
"unused":** `cli/internal/tui` is an *internal* package of the `docker/cli`
module, so compose — a separate module — **cannot import it** under Go's
internal-package rule. It's not a missed opportunity for compose; it's
unreachable (compose would use `go-runewidth`, already an indirect dep,
`compose/go.mod:97`). Within cli it *is* used (`image/tree.go`, `image/push.go`)
but jsonmessage is vendored from moby and measures nothing. One correction to
the report's praise: `tui.Ellipsis` truncates by **rune count**, not cell width
(`count.go:56-63`, `ln += 1` per non-escape rune) — only `tui.Width` is
cell-correct. And `cleanANSI` (`count.go:12-24`) strips only `ESC…m` (SGR); an
OSC 8 hyperlink would defeat it.

**(iii) Real bug or masked? Real, but largely masked in practice.** Compose
task IDs are `Container/Network/Volume <project-service-n>` with names
normalized to ASCII-safe charsets by compose-go; statuses are the ASCII
constants (`event.go:41-72`); braille spinner glyphs are single-cell, so rune
count == cell width for almost everything compose renders. Exposure paths:
(1) `details` carrying arbitrary error text or Unicode paths; (2) the
`✔`/`✘` status glyphs (`tty.go:635-637`, U+2714/U+2718) are East-Asian
**Ambiguous** — on terminals configured ambiguous-wide (common CJK setups) they
render 2 cells while every compose measure counts 1, misaligning all Done/Error
rows; (3) any non-ASCII menu or error line in Strategy C via the byte counter.
So: latent, low-frequency, but real — and the Java-design conclusion (one
wcwidth-based width function behind everything) **stands, strengthened**.

### #8 First-paint off-by-one — CONFIRMED

`tty.go:303-307`: `up := w.numLines + 1; if !w.repeated { up--;
w.repeated = true }`. `repeated` is initialized false (`Full`, `tty.go:41-49`),
written only at `:306`, never reset — a one-shot latch. First frame:
`numLines==0` → `up==0` → `aec.Up(0)` emits **nothing** (`aec.go:38-43`); every
later frame moves `numLines+1` (rows + header). ✔ — but see the §1(a) landmine:
the zero case is safe only because aec swallows `Up(0)`; `\x1b[0A` would move
up one line.

### The goterm-probes-stdout finding (from the brief) — CONFIRMED, B-only

- `getWinsize()` hardcodes `os.Stdout.Fd()`
  (`reference-src/goterm/terminal_sysioctl.go:16`; Windows variant likewise,
  `terminal_windows.go:16`). goterm offers no fd parameter.
- B paints to stderr (`display.Full(dockerCli.Err(), …)`, `compose.go:655`).
- `docker compose up >file` or `up | tee log` (the *exact* scenario the
  stderr-probe comment at `compose.go:643-646` was written for): the ioctl hits
  the redirected non-tty stdout, fails, `Width()`/`Height()` return −1
  (`terminal.go:195-197`, `terminal_sysioctl.go:33`), and compose clamps to
  80×24 (`tty.go:287-292`). **Nothing compensates** — compose never falls back
  to `term.GetWinsize` on the stderr fd. Confirmed real; the stderr routing
  defends against redirection while the size probe is defeated by it.
- **Correction to the report's framing:** Strategy C does **not** share this
  mismatch, because C actually paints to stdout (next section) — its stdout
  ioctl is self-consistent. The measure-what-you-draw-on lesson is right, and
  B is the offender; C's problem is different: **no fallback at all.**
  `extraLines` divides by `goterm.Width()` unguarded (`shortcut.go:379`) —
  Width can return −1, Height can return −1 or `math.MinInt32` (EOPNOTSUPP,
  e.g. VSCode debug console; `terminal_sysioctl.go:29-34`) — and `moveCursor`
  casts the resulting negative row through `uint(y)` (`ansi.go:55`), emitting
  an astronomically large row number that terminals clamp to the bottom edge.
  B's `<= 0 → 80×24` clamp happens to catch both sentinels; C has no such
  clamp.

---

## 3. Strategy C corrections (stream, gating, raw input)

Three of the report's Strategy C claims are wrong; together they change the §6
table and part of §5.

1. **C writes to stdout, not stderr.** Every C helper is `fmt.Print` →
   `os.Stdout`: `saveCursor`/`restoreCursor`/`moveCursor`/`clearLine`/
   `moveCursorUp`/`moveCursorDown`/`carriageReturn` (`ansi.go:28-91`) and the
   menu/error prints themselves (`shortcut.go:57,162`). The §6 row "Output
   stream: C = stderr" is incorrect. This is coherent with the system: the
   container **logs** the menu overlays also flow to stdout
   (`logs.go:134-136`), while B's progress block is on stderr. During `up`,
   stderr carries the startup block, then stdout carries logs + menu.
2. **C's terminal detection is not `Err().IsTerminal()`.** The menu is gated on
   `dockerCli.Out().IsTerminal()` (`cmd/compose/up.go:87`) **and**
   `dockerCli.In().IsTerminal()` plus `display.Mode != "plain"`
   (`cmd/compose/up.go:350`), under the `--menu` flag / `COMPOSE_MENU` env
   default-true (`:92-98,176`). Separately, all of C's cursor motion is behind
   the global `disableAnsi` kill switch (`ansi.go:26`), set by `SetANSIMode`
   which probes **stdout** (`colors.go:66-80`). The §6 row "Terminal detection:
   same [as B]" is incorrect.
3. **Raw keyboard mode does not come from `cli/streams`.** The report presents
   `stream.go`'s `setRawTerminal` as "the precondition for any single-keypress
   UI." Compose's menu never touches it: `eiannone/keyboard` opens **`/dev/tty`
   directly** (`reference-src/keyboard/keyboard.go:135-141`), applies its own
   termios raw mode — clearing `ECHO|ICANON|ISIG|IEXTEN` and the input
   translations (`keyboard.go:158-174`) — and reads via `O_ASYNC`+`SIGIO`
   (`:144-151,177-199`). Because **ISIG is cleared, the kernel no longer turns
   Ctrl-C into SIGINT** — which is precisely why `HandleKeyEvents` receives
   Ctrl-C as a key event and manually re-injects `syscall.SIGINT` into the
   signal channel (`shortcut.go:343-350`). The report describes the SIGINT
   send but misattributes the raw-mode mechanism; the `NORAW` env var belongs
   to the cli/streams path only (`stream.go:54`) and does not affect the menu.
4. Smaller: `clearNavigationMenu` loops `for range height` of
   `moveCursorDown(1)+clearLine()` (`shortcut.go:193-204`) — it wipes **every
   line from the cursor to the bottom of the screen**, not just the menu rows.
   Combined with running Before/After around *every* log write
   (`Decorate`, `shortcut.go:112-118`), this is a significant flicker source
   the report's anti-flicker analysis doesn't cover for C.

---

## 4. Missed by the first pass (material for a Java reimplementation)

1. **Windows VT enablement.** ANSI output on Windows consoles requires
   `SetConsoleMode(fd, … | ENABLE_VIRTUAL_TERMINAL_PROCESSING)` — moby/term
   does this (`cli/vendor/github.com/moby/term/term_windows.go:46-49`, with
   `ENABLE_VIRTUAL_TERMINAL_INPUT` validation `:32-33`). goterm sizes Windows
   consoles via `GetConsoleScreenBufferInfo` **window rect** (not buffer size)
   (`terminal_windows.go:14-26`). §10's "no JNI needed for output" is only true
   on POSIX; a Java port targeting Windows needs the SetConsoleMode call
   (FFM/JNA) or must require Windows Terminal, and this is the single biggest
   §10 omission.
2. **The CSI-parameter-0 family of hazards** (§1(a) above): `Up(0)` must emit
   nothing; `\x1b[0A` moves 1; `Column(0)`/`Position(_, 0)` rely on 0→1
   clamping; `moveCursor`'s `uint()` cast turns negative rows into garbage
   parameters (`ansi.go:55`). A Java port needs explicit guards.
3. **Frame writes are not atomic.** Only the prologue is batched; the body is
   one write per row to unbuffered stderr. A Java port should render the whole
   frame to a buffer and flush once (the report's §10 suggests buffering for
   *testability* but not for output batching — they're the same fix).
4. **Unresolvable width overflow silently breaks the model.** When truncation
   gives up (`tty.go:429-431`), `timerPad = max(…, 1)` (`:409`) lets the line
   exceed terminal width, wrap, and desync `numLines`. A port should treat
   "line still too wide after truncation" as a hard clamp, not a shrug.
5. **A's non-TTY downgrade is inside `Display`:** progress-bearing messages are
   dropped entirely when not a terminal (`jsonmessage.go:141-143`) — distinct
   from the 110-col bar gate, and the reason piped `docker pull` output is
   clean. The report's §6 row conflates the two.
6. **The ticker paints unconditionally** — no dirty flag; 10 fps of full-frame
   writes even when nothing changed. Fine for compose; a Java library may want
   a dirty-bit to skip identical frames (cheap because the frame is already
   computed as pure data).
7. **`--menu`/`COMPOSE_MENU` gating and `disableAnsi`** (§3 above) — the
   report's "gate all cursor motion behind one isTerminal boolean" advice is
   right, but the real system has *three* independent gates (Err probe for B,
   Out probe for `disableAnsi`, Out+In+mode for the menu); a port should
   deliberately unify them.
8. **Signal re-synthesis under raw mode** — once ISIG is cleared, Ctrl-C/Ctrl-Z
   are the application's job (`shortcut.go:343-355`, `handleCtrlZ`). In Java
   this means: after raw-mode via FFM/JNA termios on `/dev/tty`, you must map
   key bytes back to signal semantics yourself, and restore termios on every
   exit path (SURVEY's restore-is-first-class point applies to *input* state
   too, not just cursor visibility).
9. **goterm's error sentinels** (−1, `math.MinInt32`) and the plan9/solaris
   hardcoded 80×24 (`terminal_nosysioctl.go`) — any size API for Java should
   return an Optional/fallback type, never sentinel ints.

---

## 5. Verdict

**Not safe to build from strictly as-is** — Strategies A and B's mechanics and
both headline findings (stdout-size-probe bug; width-measurement bug) are
confirmed and even understated, but three corrections change design inputs: the
spinner is frame-rate-coupled (claim #3 false — the "decouple" advice loses its
existence proof), Strategy C's stream/gating/raw-input model is materially
different (stdout + Out/In gating + `/dev/tty` termios with manual SIGINT
re-synthesis), and truncation rune-safety is partial (details byte-slicing) —
plus §10 omits the Windows `SetConsoleMode` requirement and the CSI-0 hazards.

---

## 6. Corrected summary rows (§6 cross-cutting table)

Only rows that change:

| Question | Strategy A | Strategy B | Strategy C |
|---|---|---|---|
| **Output stream** | stdout (`pull.go:109`) | **stderr** (`compose.go:655`) | **stdout** — `fmt.Print` throughout (`ansi.go:28-91`, `shortcut.go:57,162`) |
| **Terminal detection** | `streams.Out` fd/tty via `term.GetFdInfo` (`display.go:64`, `stream.go:19`) | `Err().IsTerminal()` (`compose.go:654`) | `Out().IsTerminal()` **and** `In().IsTerminal()` + `--menu`/`COMPOSE_MENU` (`up.go:87,350`); cursor motion killed by `disableAnsi` set from an **Out()** probe (`colors.go:66-80`) |
| **Size fallback** | width 200 (`jsonmessage.go:237`) | 80×24 (`tty.go:287-292`) | **none** — unguarded ÷`goterm.Width()` (`shortcut.go:379`); −1 / `MinInt32` sentinels flow into `uint()` casts (`ansi.go:55`) |
| **Spinner/animation** | n/a | advances **on every read** after the first 100 ms — clock never reset (`spinner.go:45,56-59`); rate = paint rate | n/a |
| **Width truncation safety** | n/a (no measurement) | rune-safe for taskID only (`tty.go:526-545`); details **byte-sliced** (`:511-513`); layout mixes bytes+runes (`:338` vs `:349`, `:436-444`, `:466`) | bytes (`ansi.go:94`) |

---

## 7. SURVEY.md flags (not edited — for David to apply)

1. **Design decisions › anti-flicker baseline** — "decouple spinner timing from
   frame rate _(docker/compose)_": the advice is right but the attribution is
   wrong; compose's spinner is coupled to the paint rate (spinner.go clock
   never reset). Re-tag as _(gap found in docker/compose)_, like the wcwidth
   item.
2. **Matrix › width-measure row** — refine: the ttyWriter block mixes **bytes
   and runes** (not runes only), details truncation can emit invalid UTF-8, and
   `internal/tui/count.go` is *module-internal to cli* — compose cannot import
   it (and its `Ellipsis` is rune-based anyway).
3. **Matrix › seam / renderer-select** — "by `Err().IsTerminal()`" is true for
   the block; the menu path selects by `Out()`+`In()`+`COMPOSE_MENU`, and
   `disableAnsi` derives from an `Out()` probe. If the matrix grows a
   per-surface "stream" row: block=stderr, menu+logs=stdout.
4. **Matrix › input row** — add the mechanism: `/dev/tty` opened directly, own
   termios raw with **ISIG cleared**, SIGIO async reads, manual SIGINT
   re-synthesis. This feeds the jvm-hard-parts row (add: Windows
   `SetConsoleMode` VT enablement; `/dev/tty` access).
5. **Matrix › non-tty-fallback (jsonmessage)** — "plain (bar suppressed)"
   conflates two gates: non-TTY drops progress messages entirely
   (`jsonmessage.go:141-143`); the <110-col bar suppression is a TTY-mode width
   gate.
6. **Design decisions › measure-on-the-fd-you-draw-on** — still correct, but
   sharpen: the mismatch is **Strategy B only**; C is stdout-consistent but has
   **no size fallback at all** — the Java rule should be "same fd **and** a
   sane fallback."
7. **Open questions › Windows** — now has on-disk evidence:
   `moby/term/term_windows.go:46` (`ENABLE_VIRTUAL_TERMINAL_PROCESSING`, plus
   `DISABLE_NEWLINE_AUTO_RETURN` handling) and goterm's window-rect sizing.
