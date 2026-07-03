# Bubble Tea — live terminal rendering, reverse-engineered

Analysis pass (Part 1). Every claim carries a `file:line` I read directly. Paths are relative to `/Users/dblevins/work/docker/`.

## Provenance note (read this first — it bounds every claim below)

There are **three** code layers, and one of them is **not on disk**:

- `bubbletea/` (v2.0.8, `fc707bb`) and `bubbletea-v1/` (v1.3.9, `ffa0502`) — fully read.
- `charmbracelet-x/` — `ansi/`, `cellbuf/`, `term/`, `input/`, `conpty/` — fully read.
- **`github.com/charmbracelet/ultraviolet` (imported as `uv`)** — the module the **v2 renderer actually delegates to** for the cell grid, the diff, the cancel-reader, and the input parser (`bubbletea/cursed_renderer.go:13`, `tea.go:32`). It is **not present** on disk and Go/the module cache is not installed here (`go: command not found`), so I could not read it. It pins to `v0.0.0-20260703...` in `bubbletea/go.mod:9`.

The task brief pointed me at `charmbracelet-x/cellbuf` as "the cell grid + diff behind the v2 renderer." That is **half true and worth stating precisely**: v2 no longer imports `x/cellbuf` — it imports `ultraviolet`. But `x/cellbuf` is the **same author's earlier, standalone implementation of the identical algorithm** (same type names — `Screen`, `Buffer`, `Cell`, `TerminalRenderer`-shaped API; same ncurses `transformLine`/`hashmap`/`scrollOptimize` machinery). I therefore treat **`cellbuf/screen.go` + `hashmap.go` + `hardscroll.go` as the canonical, readable specification of the v2 cell-diff engine**, and I flag every place where I am reasoning from cellbuf-on-disk rather than from the ultraviolet bytes v2 links. Where a claim depends on `uv.*` behavior I cannot see, I mark it **[inferred: uv]**.

---

## Summary table

| Dimension | v2 (cursed) | v1 (standard) |
|---|---|---|
| Painting model | **cell-grid diff** (ncurses-style, per-cell, per-line-span) via `uv`/cellbuf | **line diff** (compare `[]string` line-by-line, skip unchanged) |
| Cursor motion | **mixed & cost-minimized** — tries CUP / relative / `\r`+relative / home+relative, picks shortest (`cellbuf/screen.go:192`) | **relative** — `CursorUp(n-1)` to top, `\r` per line (`standard_renderer.go:174`) |
| Repaint cadence | timer: `time.Ticker` at fps in `Program.startRenderer` (`tea.go:1409`) | timer: ticker **inside** renderer goroutine (`standard_renderer.go:99,147`) |
| Coalescing | Update stores a `View`; ticker flushes last one → intermediate frames **dropped** | Update stores a `string`; ticker flushes last one → dropped |
| Size source | `ioctl(TIOCGWINSZ)` (`term/term_unix.go:65`) | same |
| Resize | `SIGWINCH` → `checkResize` → `WindowSizeMsg` → `renderer.resize` (`signals_unix.go`, `tty.go:109`) | same, but truncates to width only |
| Vertical overflow | drop top lines from cell buffer (`cursed_renderer.go:315`) or alt-screen | truncate to `height` (`standard_renderer.go:186`) |
| Width measure | **grapheme** (`ansi.StringWidth` → uniseg clustering) + wcwidth fallback | same `ansi` pkg |
| Wide-char correct | **yes** — width stored per cell, continuation = empty cell | partial (truncate only) |
| Sync update 2026 | **yes** — probed & used (`tea.go:1109`, `cursed_renderer.go:528`) | **no** (grep clean) |
| Alt screen | yes, `?1049` (`ansi/mode.go:597`) | yes, `?1049` |
| Pinned lines | `InsertAbove` scroll-up + `IL` (`cursed_renderer.go:707`); no public scroll-region API | `SyncScrollArea`/`ScrollUp` via **DECSTBM** (deprecated) (`standard_renderer.go:573`) |
| Input | raw-keyboard; kitty protocol + modifyOtherKeys; cancelreader | raw-keyboard; no kitty |
| Non-TTY | runs; opens `/dev/tty` or degrades; `WithoutRenderer` = plain | same |
| Seam | `renderer` interface (`renderer.go:18`) + Model/Update/View | `renderer` interface + Model/Update/View(**string**) |
| Concurrency | 4 goroutine classes + single `msgs` chan + per-renderer mutex | same shape |

---

## 1. Escape codes — exact sequences and where they come from

**All sequences are sourced from `charmbracelet-x/ansi`** — either hardcoded string consts or small builder funcs. Bubble Tea itself hardcodes almost nothing; it references `ansi.*`.

Verified constants/builders in `x/ansi`:
- Erase: `EraseScreenBelow = "\x1b[J"` (`ansi/screen.go:38`), `EraseLineRight = "\x1b[K"` (`:71`), `EraseEntireLine = "\x1b[2K"` (`:73`).
- Cursor: `CursorHomePosition = "\x1b[H"` (`ansi/cursor.go:287`), `func CursorUp(n)` (`:67`), `func CursorPosition(col,row)` (`:263`).
- Alt-screen: `ModeAltScreen = 1047` and **`ModeAltScreenSaveCursor = 1049`**, `SetModeAltScreenSaveCursor = "\x1b[?1049h"` (`ansi/mode.go:595-599`).
- **Synchronized output 2026**: `ModeSynchronizedOutput = DECMode(2026)`, `SetModeSynchronizedOutput = "\x1b[?2026h"`, `RequestModeSynchronizedOutput = "\x1b[?2026$p"` (`ansi/mode.go:620-624`). The `$p` suffix is **DECRQM** (request-mode) — this is the probe.
- **Unicode core 2027**: `ModeUnicodeCore = DECMode(2027)`, set/reset/request at `ansi/mode.go:633-637`.
- Scroll region: **DECSTBM** `SetTopBottomMargins(top,bot) → "\x1b[<t>;<b>r"` (`ansi/screen.go:170-179`), aliased `DECSTBM` (`:182`).
- SGR: full builder in `cellbuf/cell.go` `Style.Sequence()` / `DiffSequence()` (`cell.go:185,242`) producing `ansi.Style` sequences; reset is `ansi.ResetStyle`.
- OSC: window title `ansi.SetWindowTitle` (`cursed_renderer.go:129`), hyperlinks `ansi.SetHyperlink(url,params)` (`cellbuf/screen.go:734`), clipboard `ansi.SetSystemClipboard`/`RequestSystemClipboard` (`tea.go:811-820`), progress bar OSC 9;4 via `ansi.SetProgressBar` (`cursed_renderer.go:790`).
- Kitty keyboard: `ansi.KittyKeyboard(flags,1)` + `ansi.SetModifyOtherKeys2` + `ansi.RequestKittyKeyboard` (`cursed_renderer.go:136-139,386-392`).
- Mouse: `ansi.SetModeMouseButtonEvent`/`SetModeMouseAnyEvent`/`SetModeMouseExtSgr` (SGR 1006) (`cursed_renderer.go:124-126`).

**v2 vs v1 for 2026**: v1 greps **clean** for `2026`, `2027`, `SynchronizedOutput`, `UnicodeCore`, and `kitty` — verified (`bubbletea-v1/*.go` grep returns nothing). v1 is 2026-blind and has no grapheme/kitty support. This is the single biggest capability delta.

**Scroll-region APIs (DECSTBM) — what happened in v2**: v1 exposed them as public commands `SyncScrollArea`, `ScrollUp`, `ScrollDown`, `ClearScrollArea`, all now marked **`Deprecated`** (`standard_renderer.go:571,688,706,723,746`); they wrote DECSTBM directly (`standard_renderer.go:579,609`). v2 **removed the public API entirely** — there is no `SyncScrollArea`/`ScrollUp` in v2. DECSTBM did not disappear from the mechanism, though: the cell engine still emits it internally for scroll optimization inside `scrolln` (`cellbuf/hardscroll.go:82,87,112,117`). The design moved scroll-region from a *user-facing rendering mode* to an *internal diff optimization*.

---

## 2. Painting model

### v2 — true cell-grid diff (ncurses lineage)

`cursedRenderer` holds a `uv.ScreenBuffer` (`cursed_renderer.go:22`). Each frame:
1. `render(View)` just stores the view struct under mutex (`cursed_renderer.go:579-584`) — no drawing.
2. `flush()` (`cursed_renderer.go:257`): builds a styled string from `view.Content`, clears the cell buffer, `content.Draw(s.cellbuf, ...)` rasterizes text→cells (`:310-311`), then `s.scr.Render(s.cellbuf.RenderBuffer)` runs the diff **[inferred: uv — the Render+diff is in ultraviolet]**, then `s.scr.Flush()`.

The **readable specification of that diff** is `cellbuf/screen.go`, which is a faithful port of ncurses `doupdate`:
- **Cell** = `{Rune, Comb []rune, Width, Style, Link}` (`cellbuf/cell.go:16-35`). Wide chars: the lead cell has `Width==2`; the trailing continuation is an **empty cell** (`Rune==0, Width==0`) that `putAttrCell` explicitly refuses to emit (`screen.go:663-669`, comment: "Zero width cells are used for wide characters that are split into multiple cells").
- **Dirty tracking**: `SetCell` compares against `curbuf` and records a per-line `lineData{firstCell,lastCell}` touched span in `s.touch` (`screen.go:468-487`) — dirty is tracked per **cell span within a line**, not per whole line.
- **Diff/flush decision** (`transformLine`, `screen.go:927-1128`): finds first & last differing cell, then makes JLine-style **cost decisions** choosing among: overwrite in place, `EL`/`EraseLineRight` (`el0Cost`, `:917`), `EL 1`/`EraseLineLeft`, `ICH`/`InsertCharacter`, `DCH`/`DeleteCharacter`, `ECH`/`EraseCharacter`, `REP`/`RepeatPreviousCharacter` (`emitRange`, `:744-811`). Each branch literally compares `len(seq)` of alternatives and picks the cheapest.
- **Cursor motion cost** (`moveCursor`, `screen.go:192-244`): tries CUP-absolute, relative (`CUU/CUD/CUF/CUB/VPA/HPA`, optionally hard-tabs and backspace), `\r`+relative, and home+relative — emits the **shortest**. `notLocal` (`:24`) decides when a long jump justifies absolute CUP. This is JLine's "is it cheaper to move or to rewrite" taken much further.
- **`hardscroll.go` — scroll optimization**: `scrollOptimize` (`:11`) builds a **hash of each line** (`hashmap.go:8`), matches unique old/new line hashes to detect that a block of lines merely *scrolled* (`updateHashmap`, `:32`; `growHunks`, `:157`; `costEffective`, `:240`), and then emits a real scroll (`ansi.ScrollUp/Down`, `DeleteLine`/`InsertLine`, `ReverseIndex`, `\n`, or DECSTBM-bounded region) instead of repainting every line (`scrolln`, `:71`). This is exactly ncurses `_nc_hash_map`/`_nc_scroll_optimize`. **Only enabled for alt-screen** (`screen.go:1353-1358`) and disabled on Windows (`cursed_renderer.go:606`, "bugs in some terminals").
- **Global skip**: `render()` early-returns if `!clear && len(touch)==0 && no mode changes` (`screen.go:1268-1275`).

### v1 — line diff

`standardRenderer` stores the last frame as `lastRender string` + `lastRenderedLines []string` (`standard_renderer.go:36-37`). `flush` (`:161`):
- Skip whole frame if `buf == lastRender` (`:165`).
- `CursorUp(linesRendered-1)` to top, or `CursorHomePosition` in alt-screen (`:174-178`).
- Split new content on `\n`; **per line**, `canSkip` if `lastRenderedLines[i] == newLines[i]` → just move down with `\n` (`:213-223`). Changed lines: truncate to width (`ansi.Truncate`, `:241`), append `EraseLineRight` if shorter than width (`:244-252`).
- `ignoreLines` map lets specific line indices be skipped so external writers own them (`:217`, `setIgnoredLines` `:510`).
- Queued `printLineMessage` lines are dumped above the frame and force a repaint (`:190-210`, `handleMessages:663`).

### The delta and (from comments) why

- **`View()` return type changed from `string` (v1) to a declarative `View` struct (v2)** (`tea.go:84-190`). The struct carries `AltScreen`, `MouseMode`, `ReportFocus`, `WindowTitle`, `Cursor`, `ForegroundColor`, `ProgressBar`, `KeyboardEnhancements` — everything that in v1 was an imperative side-effect command (`EnterAltScreen`, `EnableMouseCellMotion`, …). v2's `flush` diffs these fields against `lastView` and emits only the mode changes that differ (`cursed_renderer.go:320-458`, `viewEquals` `:803`). This is the deepest architectural change: **rendering state became declarative and diffable**, not imperative.
- **Line diff → cell-grid diff**: gains correct handling of wide chars, styled sub-line updates, and scroll detection; loses nothing except simplicity.

---

## 3. Repaint cadence & coalescing (PRIORITY)

**Defaults**: `defaultFPS = 60`, `maxFPS = 120` (`renderer.go:12-14`, also v1 `standard_renderer.go:18-19`). `WithFPS` clamps `<1 → 60`, `>120 → 120` (`options.go:142`, `tea.go:626-630`).

**The loop (v2)** — this is the whole answer:

```
eventLoop (tea.go:743): for each msg on p.msgs:
    Update(msg) → (model, cmd)         // tea.go:872
    cmds <- cmd                        // tea.go:877
    p.render(model)                    // tea.go:880  → renderer.render(model.View())
```
`renderer.render(View)` **only stores** the view under a mutex (`cursed_renderer.go:579-584`). It does **not** touch the terminal.

A **separate goroutine** started in `startRenderer` (`tea.go:1409-1421`) ticks:
```
case <-p.ticker.C:
    p.flush()                 // program-level outputBuf (mode queries etc.)
    p.renderer.flush(false)   // the actual diff + terminal write
```
So:
- **Every Update triggers a `View()`** (computed at message rate) — but not a paint.
- Between two ticks, N messages overwrite `s.view` N times; the tick flushes **only the last stored view**. Intermediate frames are **dropped, never queued**.
- `flush` further no-ops if `viewEquals(lastView, view)` and bounds unchanged (`cursed_renderer.go:287-290`), and the underlying `render()` no-ops on empty `touch` (`screen.go:1268`). So a steady view costs nothing per tick.

**Under a producer faster than fps**: producer `Cmd`s each run in their own goroutine and `p.Send` blocks on the unbuffered `msgs` channel (`tea.go:721-733,1183-1188`). eventLoop drains as fast as it can (running Update + View each time), but the terminal write is rate-limited to fps. Net: the *model* sees every message; the *terminal* sees ≤fps frames of the latest state. This is the coalescing the other tools lacked.

**v1 difference**: the ticker lives **inside** the renderer (`standard_renderer.go:86-99` `start()`→`go r.listen()`; `:147` ticker→`flush`). v1 `render` = `renderer.write(string)` stores into `buf` (`:303`, called `tea.go:496`). v2 moved the ticker up into `Program` and made the renderer passive. Same coalescing semantics; cleaner ownership.

**Notable cost**: `View()` runs on every message even though most frames are dropped. They chose message-rate View + frame-rate paint rather than gating View on the tick.

---

## 4. Vertical overflow & window size

- **Size**: `ioctl(TIOCGWINSZ)` via `unix.IoctlGetWinsize` (`term/term_unix.go:64-70`), surfaced as `term.GetSize` and called at startup (`tea.go:1045`) and on every resize (`tty.go:115`).
- **SIGWINCH**: `listenForResize` registers `signal.Notify(sig, syscall.SIGWINCH)` and on each signal calls `checkResize` (`signals_unix.go:15-33`), which re-queries size and sends `WindowSizeMsg` (`tty.go:109-127`).
- **Does resize force a full repaint?** Yes. v2 `renderer.resize` calls `s.scr.Erase()` then `Resize` (`cursed_renderer.go:619-630`, comment explains: alt-screen always redraws because terminals scroll and lose content; inline mode only redraws if width changed). cellbuf `Resize` sets `clear=true` when width changes or alt-screen (`screen.go:1461-1470`). v1 `handleMessages`→`WindowSizeMsg` sets width/height and `repaint()` (`standard_renderer.go:630-635`).
- **Vertical overflow**: v2 drops lines off the **top** of the cell buffer when content taller than terminal (`cursed_renderer.go:315-317`, `s.cellbuf.Lines = s.cellbuf.Lines[frameHeight-s.height:]`). v1 also drops from the top (`standard_renderer.go:186-188`, comment: "we can't navigate the cursor into the scrollback buffer").
- **Windows**: `signals_windows.go` exists; SIGWINCH is not delivered — v1 comment (`standard_renderer.go:236-239`) warns width is only captured at init on Windows. Resize on Windows relies on ConPTY/input-driven size events **[inferred: uv/conpty — `charmbracelet-x/conpty` exists but the size-event path is in ultraviolet's reader]**.
- **Fallback**: if `width/height <= 0`, cellbuf `NewScreen` tries `term.GetSize` on the writer if it's a `term.File` (`cellbuf/screen.go:590-600`); otherwise clamps to 0 (width truncation simply disabled).

---

## 5. Horizontal fit & display width

- **Package-level `ansi.StringWidth` always uses `GraphemeWidth`** (uniseg grapheme clustering) (`ansi/width.go:65-67`). `stringWidth` walks the ANSI parser state machine, and for printable/UTF-8 runs calls `FirstGraphemeCluster(s[i:], m)` (`width.go:88-104`). So ANSI escapes are skipped and width is per grapheme cluster.
- **Two methods** (`ansi/method.go:32-35`): `WcWidth` (wcwidth via `mattn/go-runewidth`) and `GraphemeWidth` (grapheme clustering, additionally uses `clipperhouse/displaywidth`). `wcOptions` sets `StrictEmojiNeutral: true`; East-Asian ambiguous width off by default but toggled by env `RUNEWIDTH_EASTASIAN` (`method.go:11-25`). ZWJ/flag emoji handled correctly by the grapheme path — the tests assert `😀`→2 and `🏳️‍🌈`→2 (`ansi/method_test.go:21-23`).
- **cellbuf stores wide chars** as lead cell `Width=2` + trailing empty cell; the lower-right-corner case disables autowrap around the wide write (`putCellLR`, `cellbuf/screen.go:699-711`).
- **Does width disagree with the terminal, and does it probe?** **Yes it probes.** Default cellbuf `Method` is `WcWidth` (iota 0), but bubbletea **queries mode 2027** at startup (`tea.go:1113`) and, on the terminal's `ModeReportMsg` response, calls `setWidthMethod(ansi.GraphemeWidth)` **and** emits `SetModeUnicodeCore` to put the terminal itself into grapheme mode (`tea.go:794-798`, `cursed_renderer.go:690-703`). This is exactly the "probe, don't guess" behavior JLine lacks (JLine's mode-2027/emoji-CPR probing is the closest analog; bubbletea does the 2027 DECRQM probe but relies on the mode report rather than a CPR round-trip for width). Cursor-position probing exists separately: `RequestCursorPositionReport` (CPR/DSR) at `tea.go:856`.

---

## 6. Cursor & restore

- **Hide/show around frames**: two strategies in `flush` (`cursed_renderer.go:493-558`). If 2026 is available, wrap updates in `?2026h … ?2026l` and toggle cursor visibility outside it. If not, wrap the update bytes with `ResetModeTextCursorEnable`(hide)/`SetModeTextCursorEnable`(show) to mask cursor flicker. cellbuf independently wraps a batch in `HideCursor`/`ShowCursor` when it has queued visible text (`screen.go:1396-1403`).
- **Raw mode termios** (`term/term_unix.go:19-41`, replicates `cfmakeraw`): clears `IGNBRK|BRKINT|PARMRK|ISTRIP|INLCR|IGNCR|ICRNL|IXON` (Iflag), `OPOST` (Oflag), `ECHO|ECHONL|ICANON|ISIG|IEXTEN` (Lflag), `CSIZE|PARENB` (Cflag); sets `CS8`, `VMIN=1`, `VTIME=0`. Old state saved and restored via `IoctlSetTermios`.
- **Restore on exit/panic**: `shutdown` (`tea.go:1241-1265`, `sync.Once`) cancels the reader, stops the renderer (final flush unless killed), and `restoreTerminalState`. Panic handling: `recoverFromPanic` (`tea.go:1269`) sends `ErrProgramPanic`, calls `shutdown(true)`, prints stack with `\r\n` line endings (because raw mode may still be on), optional dump file on `TEA_DEBUG`. `Run` installs the recover via `defer` (`tea.go:1026-1033`); goroutine panics use `recoverFromGoPanic` (`:1294`).
- **Alt-screen enter/exit ordering** (`cursed_renderer.go:645-671`): enter = SaveCursor → `?1049h` → set fullscreen → Erase; exit = Erase → clear fullscreen → `?1049l` → RestoreCursor. `close` deliberately goes to the bottom of the screen first "regardless of alt-screen" to avoid leaving the cursor mid-screen on terminals without alt-screen support (`:161-171`). Kitty keyboard is reset on every screen switch because the spec keeps separate registries for main/alt (`:378-394`, `:514-518`).

---

## 7. Flicker techniques (enumerated)

1. **Framerate batching** — coalesce many Updates into ≤fps paints (§3).
2. **Diff minimization** — cell-grid diff emits only changed spans + shortest cursor moves + `ECH`/`REP`/`ICH`/`DCH` (§2).
3. **Synchronized output ?2026** — wrap each frame atomically when supported (`cursed_renderer.go:528-555`).
4. **Hide-cursor wrapping** — mask cursor movement during a frame (`cursed_renderer.go:533-557`, `screen.go:1396-1403`).
5. **Buffered single flush per frame** — everything accumulates in `bytes.Buffer`s (`s.buf`, program `outputBuf`) and is written once per tick via `io.Copy` (`cursed_renderer.go:564-571`, `tea.go:1231`).
6. **Scroll optimization** — detect scrolled regions via line hashing and emit a scroll instead of a repaint (`hardscroll.go`).
7. **Skip-if-unchanged** at three levels: `viewEquals` (`cursed_renderer.go:287`), `render` touch-empty (`screen.go:1268`), and per-line hash reuse in scroll.
8. **`EraseLineRight` only when line shorter than width** (`standard_renderer.go:244`) — avoids emitting erase that would corrupt trailing escapes.

---

## 8. Pinned lines + scrolling logs

- **v1**: `ignoreLines` map marks line indices the renderer won't touch; `SyncScrollArea`/`ScrollUp`/`ScrollDown` write a **DECSTBM-bounded** region and `InsertLine` (`standard_renderer.go:573-618`). All **deprecated**.
- **v2**: there is no public scroll-region API. The equivalent for **permanent log lines above the UI** is `tea.Println`/`Printf` → `printLineMessage` → `renderer.insertAbove(str)` (`renderer.go:70`, `tea.go:861`). `insertAbove` (`cursed_renderer.go:707-763`) does it without corrupting the live UI by: `\r`, cursor-down to the bottom, scroll the screen up by the wrapped line count (`\n` × offset), cursor-up, `InsertLine(offset)`, then write each log line followed by `EraseLineRight`+`\r\n`, and reset the renderer's tracked position to (0,0) so the next diff repaints the UI in its new location. cellbuf has the analogous `queueAbove`/`InsertAbove` path (`screen.go:1308-1332,1505-1514`, with a TODO to "use scrolling region if available"). So v2 replaced explicit DECSTBM pinning with an insert-lines-above-and-repaint approach; the diff engine's scroll-region use is now purely an internal optimization.

---

## 9. Interactive input

- **Cancelreader**: `uv.NewCancelReader(p.input)` (`tty.go:69`) wraps the underlying `muesli/cancelreader` (confirmed by `charmbracelet-x/input/cancelreader_other.go:12` → `cancelreader.NewReader(r)`). Its trick (well-known, source not on disk): a blocking `read(2)` on stdin is made interruptible by `select`/`epoll`/`kqueue`ing over stdin **plus a self-pipe**; `Cancel()` writes to the pipe to break the read. `shutdown` calls `cancelReader.Cancel()` then waits with a **500 ms timeout** in case the cancel didn't actually unblock the read (`tty.go:97-105`, `tea.go:1249-1257`).
- **Decoding**: `uv.NewTerminalReader(cancelReader, term)` runs `StreamEvents(ctx, p.msgs)` in `readLoop` (`tty.go:74-94`) — it parses the byte stream (kitty keyboard, mouse SGR, bracketed paste, focus, DCS/OSC responses) into `uv.Event`s **[inferred: uv — parser is ultraviolet, built on `x/ansi/parser`]**. `translateInputEvent` (`input.go:8-54`) maps every `uv.*Event` to a `tea.*Msg` (KeyPress/KeyRelease, Mouse{Click,Motion,Release,Wheel}, Paste{,Start,End}, Focus/Blur, WindowSize, Capability, ModeReport, KeyboardEnhancements, …).
- **Who owns terminal writes**: only the renderer goroutine writes the UI (via the ticker calling `flush`), guarded by the renderer's `sync.Mutex`. The input `readLoop` only **produces** into `p.msgs`. The event loop runs Update/View on a single goroutine. So: **one writer goroutine (ticker), one reader goroutine (input), one signal goroutine, N transient Cmd goroutines, all funneling into the single unbuffered `p.msgs` channel**; the renderer mutex serializes the rare cross-goroutine renderer calls (resize, clearScreen, insertAbove).

---

## 10. Non-TTY fallback & color

- **TTY detection**: `term.IsTerminal(fd)` = `ioctl(TCGETS)` succeeds (`term/term_unix.go:14-17`). At startup, if stdin isn't a TTY, it **opens `/dev/tty`** via `OpenTTY` rather than giving up (`tea.go:1010-1017`, `tty.go:130`).
- **Runs when output is a pipe?** Yes. `WithInput(nil)` disables input (`options.go:40-45`); `WithoutRenderer()` installs `nilRenderer` and sends output plainly (`options.go:98`, `tea.go:1057-1059`) — explicitly "behave like a daemon if output is not a TTY." `WithOutput`/`WithInput` override the streams.
- **Color profile**: detected by the external `colorprofile.Detect(output, environ)` (`tea.go:1082-1085`) — **source not on disk**, so I cannot cite the exact env reads (`NO_COLOR`, `CLICOLOR_FORCE`, `COLORTERM`) at file:line; they live in `github.com/charmbracelet/colorprofile`. What **is** verifiable here: the profile is pushed to the renderer and to the model as `ColorProfileMsg` (`tea.go:1088-1089`), the renderer **downsamples every cell's colors to the profile** (`cellbuf/screen.go:719-723` `ConvertStyle`), and truecolor is **upgraded at runtime** when the terminal answers the `RGB`/`Tc` capability query (`CapabilityMsg` handler, `tea.go:776-784` → sets `colorprofile.TrueColor`). `WithColorProfile` forces a profile (`options.go:153`). Net: bubbletea both reads env (in colorprofile) and **probes** the terminal — the contrast with JLine (honors none) holds, but the env-var citations are external.

---

## 11. Abstraction seam & concurrency (PRIORITY)

**Contract**: `Model{ Init() Cmd; Update(Msg) (Model,Cmd); View() View }` (`tea.go:53-65`). `Cmd = func() Msg` (`:390`); `Msg = uv.Event` (any) (`:50`). Producers turn work into `Msg`s; the loop turns `Msg`s into new models and views.

**Renderer interface** (`renderer.go:18-57`): `start / close / render(View) / flush(bool) / reset / insertAbove / setSyncdUpdates / setWidthMethod / resize / setColorProfile / clearScreen / writeString / onMouse`. Implementors: `cursedRenderer` (`cursed_renderer.go:38 var _ renderer = ...`) and `nilRenderer` (`nil_renderer.go`). Selected in `Run`: `disableRenderer → nilRenderer`, else `newCursedRenderer` (`tea.go:1056-1078`). `WithoutRenderer` and injecting a custom `p.renderer` before Run are the seams; tests use both.

**Goroutines** (all created in `Run`):
| Goroutine | Source | Role |
|---|---|---|
| signal handler | `handleSignals` `tea.go:654` | SIGINT→InterruptMsg, SIGTERM→QuitMsg into `msgs` |
| resize listener | `listenForResize` `signals_unix.go:15` | SIGWINCH→checkResize→WindowSizeMsg |
| command runner | `handleCommands` `tea.go:700` | drains `cmds` chan, runs each `Cmd` in its own child goroutine, `Send`s result |
| input read loop | `readLoop` `tty.go:84` | parse stdin → `msgs` |
| renderer ticker | `startRenderer` `tea.go:1409` | fps tick → `flush` (the only UI writer) |
| main event loop | `eventLoop` `tea.go:743` | the single consumer of `msgs` |

**Synchronization**: one **unbuffered** `p.msgs` channel is the serialization point — every producer blocks until the loop accepts. `p.errs` (buffered 1) for fatal errors. `context.Context` (`p.ctx`) cancels all goroutines; `channelHandlers` waits for them at shutdown (`tea.go:395-423`). The renderer's own `sync.Mutex` guards its buffers. `atomic` `ignoreSignals` gates signal handling during suspend/release.

**Does the same Msg stream drive non-TTY mode?** Yes — the event loop and Update are identical; only the renderer swaps to `nilRenderer`. Model logic is unchanged between TTY and pipe.

---

## 12. The v1→v2 delta as a design verdict

What the authors changed, and (from code/comments) why:

1. **`View() string` → `View() View` struct** (`tea.go:84-190`). Rendering state (alt-screen, mouse, focus, title, cursor, colors, kitty, progress) became **declarative fields diffed each frame** instead of imperative commands (`EnterAltScreen`, `EnableMouseCellMotion`, `WithAltScreen`, `WithMouseCellMotion`, `WithReportFocus` — all present in v1 `options.go:109-248`, all gone in v2 `options.go`). *Verdict*: the imperative mode-toggle commands were a source of state drift; making them view fields lets one code path (`viewEquals` + per-field diff, `cursed_renderer.go:320-458`) keep the terminal consistent.
2. **Line diff → cell-grid diff** (standard→cursed). Gains: correct wide-char/emoji, styled sub-line updates, scroll detection, cursor-move cost minimization. The engine moved into a reusable library (`x/cellbuf`, then `ultraviolet`).
3. **Added**: `?2026` synchronized output (probed + used), `?2027` unicode-core + grapheme width (probed), kitty keyboard + modifyOtherKeys, hyperlinks, progress bar, terminal color/version capability queries — all gated on **runtime probes** (`tea.go:1109-1115`, `shouldQuerySynchronizedOutput:972` even allow-lists terminals and excludes SSH/Apple Terminal).
4. **Removed**: public DECSTBM scroll-region API (`SyncScrollArea`/`ScrollUp`/…, deprecated in v1), `WithANSICompressor` (`muesli/ansi/compressor`, v1 `options.go`/`standard_renderer.go:80`), and the "high-performance rendering" escape hatch — because the cell diff makes them unnecessary.
5. **Ticker ownership** moved from inside the renderer (v1) to the `Program` (v2), making the renderer a passive store+flush component.

Highest-value signal: **the winning move was making rendering state declarative and diffable, then probing the terminal for capabilities rather than configuring them by hand.** The cell-grid diff is table stakes (it's ncurses); the declarative View + capability probing is the modern insight.

---

## (a) Essential types

| Name | Role | File |
|---|---|---|
| `Program` | owns loop, channels, goroutines, renderer | `bubbletea/tea.go:426` |
| `Model` | Init/Update/View contract | `bubbletea/tea.go:53` |
| `View` (v2) | declarative frame: content + modes + cursor | `bubbletea/tea.go:84` |
| `Cmd` / `Msg` | `func()Msg` producer / `uv.Event` | `bubbletea/tea.go:390,50` |
| `renderer` (iface) | render/flush/resize/insertAbove seam | `bubbletea/renderer.go:18` |
| `cursedRenderer` | v2 cell-buffer renderer | `bubbletea/cursed_renderer.go:18` |
| `standardRenderer` | v1 line renderer | `bubbletea-v1/standard_renderer.go:27` |
| `nilRenderer` | non-TTY / no-op | `bubbletea/nil_renderer.go` |
| `uv.TerminalRenderer` / `uv.ScreenBuffer` | actual v2 cell grid + diff (not on disk) | ultraviolet (`cursed_renderer.go:22`) |
| `Screen` | ncurses-style diff engine (readable spec) | `charmbracelet-x/cellbuf/screen.go:363` |
| `Cell` | `{Rune,Comb,Width,Style,Link}` | `charmbracelet-x/cellbuf/cell.go:16` |
| `hashmap` + `scrollOptimize` | line-hash scroll detection | `charmbracelet-x/cellbuf/hashmap.go`, `hardscroll.go` |
| `ansi.Method` | WcWidth vs GraphemeWidth width calc | `charmbracelet-x/ansi/method.go:29` |
| `DECMode` consts | 1049/2026/2027 sequences | `charmbracelet-x/ansi/mode.go` |
| `term.State` | saved termios | `charmbracelet-x/term/term_unix.go:10` |
| `cancelreader.CancelReader` | interruptible stdin | `muesli/cancelreader` (via `x/input`) |
| `uv.TerminalReader` | input→event parser (not on disk) | ultraviolet (`tty.go:74`) |

## (b) Top decisions worth reproducing in a Java library

1. **Declarative view + per-field diff of terminal modes.** Return a value object describing the desired terminal state (alt-screen, mouse, cursor, title, colors); diff it against the last one and emit only the deltas. This is the cleanest thing bubbletea does and neither docker nor JLine has it.
2. **Decouple paint rate from message rate with a single ticker.** Producers mutate a stored frame; a timer flushes the latest at ≤fps. Trivial, robust coalescing under a fast producer — the exact gap in docker's 100 ms repaint and JLine's per-keystroke diff.
3. **Cost-minimized output**: choose the cheapest of {overwrite, EL, ICH/DCH, ECH, REP} per span and the shortest of {CUP, relative, CR+relative, home+relative} per move. Beats JLine's coarser prefix/suffix diff.
4. **Probe, don't guess** (`?2026$p`, `?2027$p`, capability/RGB, CPR) and upgrade behavior at runtime — banked already, but bubbletea shows the full menu and the allow-list heuristic for flaky terminals (`shouldQuerySynchronizedOutput`).
5. **Line-hash scroll optimization** for full-screen/log views — emit a real scroll instead of repainting.
6. **`InsertAbove` for permanent log lines** interleaved with a live UI without a scroll region — insert-lines-above + repaint-in-new-position.
7. **Single unbuffered channel as the serialization point** for all producers; one writer goroutine owns the terminal. Clean concurrency model.
8. **Panic recovery that restores the terminal** and re-emits with `\r\n` in case raw mode survives.

## (c) Hardest-to-port-to-JVM (only where it exceeds docker/JLine)

- **Cancelreader interruption.** JLine uses a dedicated pump thread and reads block. Bubble Tea makes the *blocking read itself* cancelable (self-pipe + `select`/`epoll`/`kqueue`). On the JVM, `System.in.read()` is not interruptible and `InterruptedException` won't unblock it; you need either NIO channels with a `Selector` over a pipe, or a native (FFM) `poll`/`read` with a self-pipe wake fd. This is the one input mechanism JLine doesn't attempt and it's non-trivial in pure Java. The 500 ms "cancel might not have worked" timeout (`tty.go:100`) is a tell that even in Go it's fiddly.
- **ConPTY / Windows resize.** No SIGWINCH; size changes arrive as input-stream events through ConPTY (`charmbracelet-x/conpty`). On the JVM you'd need the Win32 console API (FFM) — a separate resize source from the Unix ioctl/SIGWINCH path.
- **Grapheme-cluster width that matches the terminal's** (uniseg + displaywidth + the 2027 probe). Getting Java width to agree with the emulator for ZWJ/flag emoji requires an ICU-grade grapheme segmenter *and* the mode-2027 negotiation; a naive `Character`/code-point width will disagree exactly where JLine already struggles.

## What I'd copy vs avoid for a clean-room Java reimplementation

**Copy**: declarative diffable view state; ticker-decoupled coalescing (message-rate model, frame-rate paint); cost-minimized cursor motion and per-span erase/insert/repeat selection; capability probing with runtime upgrade and a terminal allow-list; buffered single-flush-per-frame; `InsertAbove`-style log interleaving; single-channel producer serialization with one terminal-writer thread; terminal-restoring panic handler.

**Avoid / reconsider**: (1) calling `View()` on **every** message even when the frame will be dropped — for a Java lib, consider gating View computation on the tick (dirty flag) to avoid wasted allocation. (2) The full ncurses hashmap scroll optimizer is a lot of code for marginal gain outside alt-screen pagers — v2 already restricts it to alt-screen; a first Java cut can skip it. (3) Emitting scroll-region/DECSTBM as a *user API* (v1) — v2's verdict is to keep it internal; follow v2. (4) Depending on an external color-profile package's env parsing implicitly — make `NO_COLOR`/`CLICOLOR_FORCE`/`COLORTERM` handling explicit and testable in your own code (bubbletea hides it in `colorprofile`, which cost me a citation).

```
REPO: bubbletea (v2 primary, v1.3.9 delta)
painting-model: cell-grid-diff (v2) | line-diff / dirty-line-skip (v1)
cursor-motion: mixed, length-minimized among CUP/relative/CR/home (v2) | relative (v1)
repaint: timer 1/fps (default 60, cap 120); ticker in Program (v2) / in renderer (v1)
size-source: ioctl TIOCGWINSZ   resize: SIGWINCH -> WindowSizeMsg -> full/width repaint (Windows: ConPTY events, no SIGWINCH)
vertical-overflow: drop-top-lines (both) + alt-screen option
width-measure: grapheme (ansi.StringWidth via uniseg) default; wcwidth available   wide-char-correct: yes (v2, 2-cell + empty continuation) | partial (v1 truncate)
flicker-controls: framerate-coalesce, cell-diff, ?2026-sync, hide-cursor-wrap, buffered-single-flush, scroll-optimize, skip-if-unchanged
synchronized-update-2026: yes+probed (v2) | no (v1, grep-clean)   alt-screen: yes (?1049) both
pinned-lines: insert-above+repaint (v2) | DECSTBM scroll-region, deprecated (v1)
input: raw-keyboard; cancelreader self-pipe interrupt; kitty+modifyOtherKeys+bracketed-paste+focus+mouse-SGR (v2); no kitty (v1)
non-tty-fallback: runs; opens /dev/tty; WithoutRenderer=plain; WithInput/WithOutput override
seam: renderer interface + Model/Update/View contract   renderer-select: nilRenderer if disableRenderer else cursedRenderer; injectable
concurrency: goroutines {signal, SIGWINCH, cmd-runner+children, input readLoop, renderer ticker(sole writer), eventLoop(sole consumer)} + 1 unbuffered msgs chan + renderer mutex + context cancel
coalescing: Update stores latest View; separate fps ticker flushes only the last stored View -> intermediate frames dropped, never queued; model sees every msg, terminal sees <=fps frames
```
