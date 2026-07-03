# Fable review — `bubbletea-live-terminal-rendering.md`

Part 2 review pass against `bubbletea/` @ fc707bb (v2.0.8), `bubbletea-v1/`
(v1.3.9 worktree), `charmbracelet-x/` — **plus `ultraviolet/` cloned at the
exact pinned commit `f5a850f9c2b7`** (from `bubbletea/go.mod`), which the
analyst could not read. Every cited file opened and checked.

**Bottom line up front:** the report is accurate — the cleanest first pass of
the survey, and its provenance discipline (flagging every `[inferred: uv]`
claim) paid off: with ultraviolet now on disk I verified **all** of those
inferences, and they hold. The cellbuf-as-spec gamble was sound. Corrections
are refinements, not reversals: uv's `Cell` is a grapheme *string* (a design
improvement over cellbuf's rune+combining array worth copying), the default
width method is ZWJ-**wrong** until the 2027 probe upgrades it (the summary
block's `wide-char-correct: yes` needs a qualifier), and there are two small
behavioral nuances in the 2026 enablement and raw-mode `OPOST` handling with
Java consequences. The coalescing answer — the payload question — is verified
exactly as written.

---

## 1. The ultraviolet verification (upgrading every `[inferred: uv]` claim)

The report treated `x/cellbuf` as the "readable specification" of the engine
v2 actually links. Verified against real uv source:

- **Same ncurses machinery, confirmed**: `transformLine`
  (`ultraviolet/terminal_renderer.go:814`), `emitRange` (`:622`), cost-based
  `moveCursor` (`:388`, seq-building variant `:1516`), `putCell`/`putCellLR`
  (`:495,552`), `scrollOptimize` invoked from `Render` (`:1234`), with
  `terminal_renderer_hashmap.go` / `terminal_renderer_hardscroll.go` mirroring
  cellbuf's files. The spec-by-proxy approach was legitimate.
- **Scroll optimization gate, confirmed and sharpened**: gated on
  `s.flags.Contains(tScrollOptim) && s.flags.Contains(tFullscreen)`
  (`terminal_renderer.go:1230-1234`), with the in-source comment linking the
  **Windows Terminal DECSTBM bug**
  (microsoft/terminal#19016) as the reason bubbletea disables it wholesale on
  Windows (`cursed_renderer.go:606`). Same shape as JLine's
  fullscreen-only gate — two independent codebases agreeing that scroll
  optimization is a fullscreen-only affair.
- **2026 emission, confirmed**: BSU/ESU written around the flush batch in
  `terminal_screen.go:276-296`, driven by the `syncUpdates` flag (`:39,529`).
- **Cancelreader, confirmed**: `uv.NewCancelReader` delegates to
  `muesli/cancelreader` on non-Windows (`ultraviolet/cancelreader_other.go:15`)
  exactly as the report inferred via `x/input`. (uv's own `poll_*.go` files are
  a separate poll-based reader, not the cancel mechanism.)
- **CORRECTION (refinement) — uv's `Cell` is not cellbuf's `Cell`.** cellbuf:
  `{Rune, Comb []rune, Width, Style, Link}` (`cellbuf/cell.go:16-35`). uv:
  `{Content string, Style, Link, Width}` where `Content` is **one grapheme
  cluster as a string** (`ultraviolet/cell.go:15-29`), wide-char continuation
  = zero-width cell (`cell.go:69`). The evolution rune-array → grapheme-string
  is itself a v1→v2-grade design signal: the cell's unit of content is the
  grapheme cluster, not the code point. **Copy uv's shape, not cellbuf's**, in
  the Java port (a `String` cluster per cell sidesteps Java's char/codepoint
  mismatch entirely).

## 2. Coalescing — the payload question: CONFIRMED exactly

- `render()` stores the view under mutex, nothing else
  (`cursed_renderer.go:578-584`).
- `eventLoop`: every message runs `Update` (`tea.go:872`) then
  `p.render(model)` (`:880`) — `View()` at message rate.
- The ticker goroutine in `startRenderer` (`tea.go:1408-1421`) is the sole
  painter: `p.flush()` then `p.renderer.flush(false)` per tick.
- `msgs` is unbuffered (`tea.go:598`); `Send` blocks on it (`:1183-1188`) —
  producers are backpressured by the event loop, never by the terminal.
- Skip-if-unchanged confirmed at both levels: `viewEquals` + bounds check
  (`cursed_renderer.go:287-290`), and uv's `Render` no-ops on empty touch.
- FPS: `defaultFPS=60, maxFPS=120` (`renderer.go:10-15`), clamp
  (`tea.go:626-630`), `WithFPS` (`options.go:142`).
- v1 same semantics, ticker inside the renderer (`standard_renderer.go:96-150`)
  — confirmed.

The one-line answer for SURVEY stands verbatim: *model sees every message,
terminal sees ≤fps frames of the latest state; intermediate frames dropped,
never queued.*

## 3. Width & probing — CONFIRMED with one sharpened caveat

- `ansi.StringWidth` is grapheme-based (`ansi/width.go:60-67`); two methods
  `WcWidth`/`GraphemeWidth` (`ansi/method.go:29-35`); `RUNEWIDTH_EASTASIAN`
  env toggle (`:20-25`); `StrictEmojiNeutral: true` (`:11-14`).
- Probe flow confirmed end-to-end: `RequestModeSynchronizedOutput +
  RequestModeUnicodeCore` sent at startup behind the allow-list
  (`tea.go:1109-1114`); `ModeReportMsg` → `setWidthMethod(GraphemeWidth)`
  (`tea.go:794-798`) → renderer emits `SetModeUnicodeCore` to switch the
  *terminal* into grapheme mode too (`cursed_renderer.go:689-704`).
- **CORRECTION (summary block): `wide-char-correct: yes` overstates the
  default.** The test the report cites actually shows the split:
  `🏳️‍🌈` = **1** under `WcWidth`, 2 under `GraphemeWidth`
  (`ansi/method_test.go:22-23`) — and the engine's default method is
  `WcWidth` until a successful 2027 report upgrades it. So ZWJ emoji are
  mis-measured *by default*; correctness is **probe-contingent**. That's the
  same philosophy JLine landed on (width follows the terminal's proven mode),
  and it's the right design — but the block should read
  `wide-char-correct: partial by default, yes after 2027 probe`.
- **Nuance the report missed:** sync updates are enabled only when the mode
  report says `ModeReset` — supported *and currently off*
  (`tea.go:788-793`). A terminal answering `Set`/`PermanentlySet` does not get
  `syncdUpdates=true`. Defensible (someone else owns the mode) but a port
  should decide this case consciously.

## 4. Everything else — CONFIRMED (verified, exact cites)

- **Escape codes**: all `x/ansi` constants verified — 1049 set/reset/request
  (`ansi/mode.go:595-600`), 2026 triple (`:618-624` — the `$p` DECRQM), 2027
  triple (`:633-637`), `EraseEntireLine` (`ansi/screen.go:73`), DECSTBM
  builder (`:170`), cursor builders (`ansi/cursor.go:67,263,287`). v1 greps
  clean for 2026/2027/kitty — reproduced. ✔
- **Raw mode** (`term/term_unix.go:19-41`): flags exactly as reported —
  including `OPOST` cleared, see §5.2 below. `isTerminal` = tcgetattr succeeds
  (`:14-17`); `getSize` = `IoctlGetWinsize(TIOCGWINSZ)` (`:64-70`). ✔
- **SIGWINCH** → `checkResize` → `WindowSizeMsg` (`signals_unix.go:15-33`,
  `tty.go:109-127`). ✔
- **Restore/panic**: `shutdown` under `sync.Once` — cancel ctx, cancel reader
  (with the 500 ms "cancel might not have worked" wait, `tty.go:97-105`),
  stop renderer, restore terminal (`tea.go:1241-1265`); panic recovery wired
  via `defer` in `Run` (`:1026-1033`). ✔
- **Alt-screen ordering**: enter = SaveCursor→`?1049h`→fullscreen→Erase; exit
  = Erase→un-fullscreen→`?1049l`→RestoreCursor (`cursed_renderer.go:645-671`);
  `close` moves to the bottom row first "regardless of alt-screen"
  (`:161-171`); kitty flags reset around screen switches. ✔
- **`insertAbove`**: scroll-up + write lines + `EraseLineRight` + `\r\n`, then
  `s.scr.SetPosition(0, 0)` so the next diff repaints the UI in place
  (`cursed_renderer.go:707-763`). ✔
- **v1 claims**: all verified — fps consts (`standard_renderer.go:17-19`),
  `lastRender`/`lastRenderedLines` (`:36-37`), listen goroutine (`:96-150`),
  whole-frame skip (`:161-165`), home-or-CursorUp rewind (`:172-178`),
  drop-top overflow (`:184-188`), `canSkip` + `ignoreLines` (`:211-224`),
  truncate + erase-only-if-shorter (`:240-252`), six `Deprecated` scroll/ANSI
  APIs, compressor import (`:12,40,76`), resize→repaint (`:630-635`). ✔
- **Seam/concurrency**: `Msg = uv.Event` (`tea.go:50`), `Cmd` (`:390`),
  renderer selection nil-vs-cursed (`:1056-1078`), `colorprofile.Detect` +
  `ColorProfileMsg` (`:1082-1089`), `RGB`/`Tc` capability → TrueColor upgrade
  (`:776-784`), CPR request (`:856`), `/dev/tty` fallback when stdin isn't a
  TTY (`:1008-1017`). Goroutine map as tabled. ✔
- The allow-list (`shouldQuerySynchronizedOutput`, `tea.go:968-987`) excludes
  **Apple Terminal and SSH sessions** — independently convergent with JLine's
  `TERM_PROGRAM=Apple_Terminal` DECRQM blacklist. Two codebases, same scar.

## 5. Missed by the first pass (minor — uv unavailability excused most)

1. **uv's grapheme-string `Cell`** (§1 above) — the most Java-relevant find of
   the review: model the cell content as a `String` grapheme cluster.
2. **`OPOST` is cleared in raw mode** (`term/term_unix.go:31`) — unlike
   JLine's `enterRawMode`, which touches no output flags. With `OPOST` off the
   kernel stops translating `\n`→`\r\n`, so bubbletea must handle newline
   mapping itself (`mapNl` optimization, `tea.go:1075-1077`;
   `SetMapNewline`, `cursed_renderer.go:605`). A Java port choosing full
   `cfmakeraw` semantics inherits this obligation; JLine's choice (leave
   `OPOST` alone) is the simpler default for an inline renderer.
3. **2026 enabled only on `ModeReset` report** (§3 above).
4. `colorprofile` env handling remains uncited (package not on disk) — the
   report flagged this honestly; the design advice ("make NO_COLOR handling
   explicit in your own code") is right regardless.

## 6. Verdict

**Safe to build from as-is** — with the summary block's `wide-char-correct`
field qualified to "partial by default, yes after 2027 probe," and uv's
grapheme-string `Cell` preferred over cellbuf's rune-array shape. All
`[inferred: uv]` claims are now verified CONFIRMED against the pinned
ultraviolet source; the coalescing answer, the v1→v2 delta, and the
declarative-View insight stand as written.

## 7. Corrected summary-block fields

```
width-measure: grapheme (ansi.StringWidth/uniseg) — but engine DEFAULT is WcWidth until 2027 probe upgrades
wide-char-correct: partial by default (ZWJ emoji = 1 under WcWidth, ansi/method_test.go:22-23); yes after probe
painting-model: cell-grid-diff — cell = { grapheme-cluster STRING, style, link, width } (uv/cell.go:15-29), continuation = zero-width cell
```
All other fields CONFIRMED as reported.
