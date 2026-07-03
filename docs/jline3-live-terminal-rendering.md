# JLine 3/4 — Live-Terminal Rendering: Mechanism Analysis

**Repo:** `/Users/dblevins/work/docker/jline3` @ `d861ac72` (~v4.3.1)
**Goal:** extract the fundamental mechanism JLine uses to repaint a terminal in place (progress, spinners, pinned status, line editing) so it can be reproduced clean-room in Java — *not* a recommendation to use JLine.

**Verification note (for the adversarial reviewer):** Every claim carries a `file:line`. I personally read and verified the core rendering/native/capability/exec files: `Display`, `DiffHelper`, `Curses`, `WCWidth`, `Status`, `AttributedCharSequence`, `InfoCmp`, the `.caps` resources, FFM `CLibrary`, `FfmSignalHandler`, `Signals`, `ExecPty`, `AbstractTerminal`/`AbstractPosixTerminal`/`AbstractUnixSysTerminal`, `TerminalBuilder`, `DumbTerminal`. The **Windows console** details (`AbstractWindowsTerminal`, FFM `Kernel32`, `NativeWinSysTerminal`) and the **reader input chain** (`NonBlockingReaderImpl`, `PumpThread`, `KeyMap`, `BindingReader`, `LineReaderImpl`) were gathered by sub-agents; I independently spot-verified the load-bearing ones (`KeyMap.java:58`, `BindingReader.java:131-136`, `AbstractWindowsTerminal.java:276-293`). Those two areas are flagged **[agent+spot-verified]** where I did not read the entire file.

---

## Summary table

| # | Question | One-line answer |
|---|----------|-----------------|
| 1 | Escape codes | Almost nothing hardcoded — resolves **terminfo capabilities** via `InfoCmp` (embedded `.caps` files) interpreted by `Curses.tputs`. Hardcoded exceptions: sync-update `\033[?2026h/l`, focus `\033[?1004h/l`, OSC color queries `\033]10;?`/`\033]11;?`. No OSC-8 hyperlinks. |
| 2 | Painting model | **Per-line dirty diff** with common-prefix/suffix (`DiffHelper`) + inter-frame line-level LCS scroll optimization. Relative cursor motion by default, absolute (`cup`) only full-screen. |
| 3 | Vertical overflow | Clamps to `min(rows, …)` per frame; `Status` uses **scroll region (DECSTBM `csr`)** to reserve bottom lines. Alt-screen (`smcup`/`rmcup`) exists as caps but `Display` itself does not switch it. |
| 4 | Width / display-width | `WCWidth` = Markus Kuhn port, **Unicode 16.0** tables; grapheme-cluster aware via JDK 21+ `BreakIterator`. Hard-wrap math in `AttributedCharSequence.columnSplitLength`. |
| 5 | Window size | FFM/JNI `ioctl(TIOCGWINSZ)`; exec `stty -a`; Windows `GetConsoleScreenBufferInfo`. **SIGWINCH-driven** (Windows: `WINDOW_BUFFER_SIZE_EVENT`→synthetic WINCH). Dumb fallback `Size(0,0)`. |
| 6 | Repaint cadence | **Event-driven** — `LineReaderImpl.redisplay()` after each key binding. No timer/debounce. |
| 7 | Cursor & restore | `civis`/`cnorm` optional; raw mode via `tcsetattr` clearing `ICANON/ECHO/ISIG/IEXTEN`; restore via `ShutdownHooks` + `Terminal.close()` restoring saved `Attributes`. |
| 8 | Flicker control | Minimal diff, mode-2026 synchronized update (full-screen), byte-mode buffering + single flush, `ensureDefaultAnsiStyle`, cursor-hide left to caller. |
| 9 | Pinned lines | `Status` = scroll-region (`csr`) reserving bottom N rows + a `MovingCursorDisplay` subclass that save/restores cursor around each paint. |
| 10 | Input | Raw mode + `NonBlockingReader` (background `PumpThread`); `KeyMap` trie + `BindingReader` with 1000 ms ambiguity timeout. |
| 11 | Non-TTY / color | `isatty`/`GetConsoleMode` detection → `DumbTerminal` (`dumb`/`dumb-color`). Color from terminfo `max_colors`; truecolor upgrade from `COLORTERM`=truecolor/24bit or `TERM` `-direct`. No `NO_COLOR`/`CLICOLOR`. |
| 12 | Seam / concurrency | SPI `TerminalProvider`; priority **ffm → jni → exec** then dumb. `Display` not thread-safe; `LineReaderImpl` guards with a `ReentrantLock`. SIGWINCH: FFM `sigaction`, JNI native, or `sun.misc.Signal` reflection. |

---

## 1. Escape codes & the terminfo/`Curses` pipeline

**Key insight: JLine does not hardcode cursor/erase/SGR sequences.** It resolves *terminfo capabilities* to strings, then interprets them with a parameterized-capability interpreter. `Display` never writes `\e[K` literally — it calls `puts(Capability.clr_eol)` (`Display.java:698`), and the actual bytes come from the terminfo entry.

### Where capability strings come from
`InfoCmp` ships **embedded `.caps` files** as classpath resources under `terminal/src/main/resources/org/jline/utils/` (`ansi.caps`, `xterm.caps`, `xterm-256color.caps`, `windows*.caps`, `screen*`, `rxvt*`, `dumb`, `dumb-color`). They are registered lazily in a static block (`InfoCmp.java:716-735`) via `setDefaultInfoCmp(s, () -> loadDefaultInfoCmp(s))`, loaded from the resource stream in `loadDefaultInfoCmp` (`InfoCmp.java:707-714`).

Resolution order for a `TERM` type (`InfoCmp.getInfoCmp`, `InfoCmp.java:603-646`): (1) previously *loaded* caps cache; (2) shell out to `infocmp -x <term>` then `infocmp <term>` (`InfoCmp.java:613,626`) — guarded by `isValidTerminalName` (`InfoCmp.java:648-658`, a CWE-88 fix since `TERM` can be attacker-controlled over telnet/ssh); (3) fall back to the embedded default `.caps` (`InfoCmp.java:640`). `AbstractTerminal.parseInfoCmp` (`AbstractTerminal.java:333-345`) drives this and defaults to `ansi` if all else fails.

The `.caps` files contain the real terminfo strings, e.g. `xterm-256color.caps`:
```
cuu=\E[%p1%dA   cuu1=\E[A   cud=\E[%p1%dB   cuf=\E[%p1%dC   cub=\E[%p1%dD
cup=\E[%i%p1%d;%p2%dH        hpa=\E[%i%p1%dG        vpa=\E[%i%p1%dd
el=\E[K   el1=\E[1K   ed=\E[J   civis=\E[?25l   cnorm=\E[?12l\E[?25h
sc=\E7   rc=\E8   csr=\E[%i%p1%d;%p2%dr   smcup=\E[?1049h   rmcup=\E[?1049l
```
(`xterm-256color.caps:6-48`) — mapping to the question's list: cursor up/down/right/left = `cuu/cud/cuf/cub` (+`1` single-step forms); column-absolute = `hpa`; absolute position = `cup`; hide/show = `civis`/`cnorm`; erase-line = `el` (to EOL) / `el1` (to BOL); erase-display = `ed`; save/restore = `sc`/`rc`; scroll-region = `csr`; alt-screen = `smcup`/`rmcup`. `parseInfoCmp` (`InfoCmp.java:660-705`) parses `name#int` (numeric, e.g. `colors#256`), `name=str` (string), and bare booleans (e.g. `am`, `xenl`) into three maps.

### `Curses.tputs` — the parameterized-capability interpreter
`Curses.doTputs` (`Curses.java:92-500`) is a full terminfo string-capability interpreter with a value stack (`ArrayDeque<Object> stack`) and 26 static+dynamic variable slots. It handles:
- `\E`/`\e`→ESC(27), `\n \r \t \b \f`, octal `\ooo` (`Curses.java:101-163`), and `^X` control chars (`Curses.java:164-169`).
- `%p1`..`%p9` push a parameter (`Curses.java:178-183`); `%'c'` push char literal; `%{nn}` push integer literal (`Curses.java:212-230`); `%d/%s/%x…` formatted output (`Curses.java:378-461`); arithmetic/logic `%+ %- %* %/ %m %& %| %^ %= %> %< %A %O %! %~` (`Curses.java:236-338`); `%?…%t…%e…%;` if-then-else (`Curses.java:339-369`); `%i` increment first two params (the 1-based `cup`/`csr` convention, `Curses.java:370-377`); `%P`/`%g` set/get variables. `$<…>` delay specs are parsed and *slept on* (`Curses.java:464-486`) — a subtle behavior worth noting.

So a call like `puts(Capability.cursor_address, l1, c1)` renders `\E[%i%p1%d;%p2%dH` with the two params (`Display.java:1002`).

### Places that DO hardcode raw sequences
- **Synchronized update (mode 2026):** `Display.java:126-127`
  ```java
  private static final String SYNC_START = "\033[?2026h";
  private static final String SYNC_END = "\033[?2026l";
  ```
  emitted only in full-screen (`Display.java:452-454`, `764-766`).
- **Focus tracking:** `\033[?1004h`/`\033[?1004l` in `AbstractTerminal.trackFocus` (`AbstractTerminal.java:426`).
- **OSC color queries:** `\033]10;?\033\\` / `\033]11;?\033\\` for default fg/bg in `AbstractPosixTerminal.java:163,174`.
- **OSC-8 hyperlinks: NOT present** — a grep for `]8;;` / `\033]8` across `terminal`, `reader`, `style` returned nothing. A clean-room lib wanting hyperlinks must add them.

---

## 2. Painting model — per-line dirty diff (`org.jline.utils.Display`)

`Display` keeps `List<AttributedString> oldLines` (the model of what is on screen, `Display.java:85`) and an integer `cursorPos` in **wrapped-line coordinates** where `columns1 = columns + 1` (`Display.java:87-88,256`); i.e. position = `row * columns1 + col`, and `col == columns` encodes "at right margin" (comment `Display.java:957-959`).

### The update algorithm (`Display.update`, `Display.java:427-790`)
1. **Byte-mode setup** (`Display.java:430-446`): if the terminal is ≥8 colors and UTF-8, all output is accumulated into a `ByteArrayBuilder` (`asAsciiAppendable`) and written directly to `terminal.output()`, bypassing `PrintWriter` encoding. Zero-allocation reusable arrays `diffResult[2]`, `lcsResult[3]`, `ansiColorState[2]` (`Display.java:113-117`).
2. **Reset** (`Display.java:457-463`): if `clear()` was called and full-screen, emit `clear_screen` + `cursor_address 0,0`.
3. **Scroll optimization** (`Display.java:475-529`): when `newLines.size() == oldLines.size()` and the terminal has insert/delete-line, it finds common header/footer runs, then a **longest-common-block** between the changed middle (`longestCommon`, `Display.java:932-954` — an O(n²) run scan, *not* a full LCS) and uses `deleteLines`/`insertLines` (`csr`-free — uses `dl`/`il`) to shift content instead of repainting. This is what makes scrolling logs cheap.
4. **Per-line loop** (`Display.java:535-753`): for each line `min(rows, max(old,new))`:
   - Strip trailing `\n` and track `oldNL`/`newNL` (`Display.java:547-554`).
   - **Intra-line diff** via `DiffHelper.diff(oldLine, oStart,oEnd, newLine, nStart,nEnd, diffResult)` (`Display.java:612`) → `cs`=common-prefix length, `ce`=common-suffix length (`DiffHelper.java:120-125`, computed char+style-aware with hidden-range protection at `DiffHelper.java:130-174`). From these, the middle is classified INSERT / DELETE / overwrite.
   - **Minimal-update tactics:** insert-with-`insertChars` when suffix stable (`Display.java:633-642`); overwrite-in-place when insert width == delete width (`Display.java:644-661`), optionally skipping runs of unchanged chars ≥ `INTRA_LINE_SKIP_THRESHOLD=8` with cursor moves (`emitOverwriteWithSkips`, `Display.java:136,873-914`); `deleteChars` when a stable suffix follows (`Display.java:679-689`); else clear-to-EOL with `clr_eol` or pad-with-spaces (`Display.java:690-705`).
   - **Diff *within* a line: yes** — prefix/suffix commonality only (not a Myers diff). The middle changed region is re-emitted or handled by insert/delete-char capabilities.
5. **Cursor motion** `moveVisualCursorTo` (`Display.java:990-1038`): relative by default (`cuu/cud/cuf/cub` or the `parm_*` multi forms via `perform`, `Display.java:826-840`, choosing single-vs-multi by *cost* in bytes, `Display.java:842-849`). **Absolute `cup`** is used *only* full-screen and only for diagonal moves (`hasCursorAddress && l0!=l1 && c0!=c1`, `Display.java:1001-1005`). Non-fullscreen vertical-down uses `\r` + `\n` repetition (`Display.java:1023-1027`).
6. **Finish** (`Display.java:754-789`): move to `targetCursorPos`, replace `oldLines` with `newLines`, `ensureDefaultAnsiStyle`, flush byte buffer, flush terminal.

### Fullscreen vs non-fullscreen
Set at construction (`Display.java:149-174`). `hasCursorAddress = fullScreen && cup!=null` (`Display.java:172`) — absolute addressing only makes sense when you own the screen. Full-screen also enables the mode-2026 wrapper (`Display.java:452`). Non-fullscreen ("inline") is what a spinner/progress renderer uses: it moves relatively and never issues `clear_screen`.

### Wrapping / last-column (am / xenl)
- `terminalWrapAtEol = auto_right_margin` (`am`), `terminalDelayedWrapAtEol = am && eat_newline_glitch` (`xenl`) (`Display.java:155-159`).
- At end-of-line the code either (delayed-wrap path) writes the last cell then relies on the terminal's deferred wrap (`Display.java:724-733`), or (immediate wrap) writes a space + `cursor_left` / uses `cup` to force the wrap (`Display.java:734-752`). `moveVisualCursorTo(int,List)` reaches the right margin by *writing the actual last character* because there is "no portable way to move to the right margin" (`Display.java:961-977`).
- **Windows wide-buffer quirk:** if the console *buffer* is wider than the visible window, auto-wrap happens at buffer width, so `resize` disables wrap reliance (`Display.java:266-273`).

---

## 3. Vertical overflow

- **`Display` clamps.** The per-line loop runs `numLines = Math.min(rows, Math.max(oldLines.size(), newLines.size()))` (`Display.java:533`) — content beyond `rows` is simply not drawn. There is no viewport scrolling inside `Display` beyond the insert/delete-line optimization.
- **No alt-screen switch inside `Display`.** `smcup`/`rmcup` exist as capabilities but `Display` never emits them; entering the alternate screen is left to the application.
- **Scroll region (DECSTBM `csr`) is used only by `Status`** (see §9). `Display`'s scroll optimization uses `dl`/`il` (delete/insert line), not `csr`.
- Zero-size guard: `resize` maps a 0 dimension to `rows=1, columns=MAX_VALUE-1` (`Display.java:248-251`) so a dumb `Size(0,0)` renders as one unbounded line.

---

## 4. Horizontal fit & display width (`org.jline.utils.WCWidth`)

- **Port of Markus Kuhn's `wcwidth()`** (`WCWidth.java:29-34` doc; algorithm `WCWidth.java:107-124`): control (<32, DEL range) → −1; combining/format table → 0; East-Asian Wide/Fullwidth table → 2; else 1. Binary search `bisearch` (`WCWidth.java:885-898`).
- **Unicode version: 16.0.** Tables generated from `UnicodeData.txt`, `EastAsianWidth.txt`, `emoji-data.txt` (`WCWidth.java:39-49`). `combining[]` = 369 intervals (Mn/Me/Cf − softhyphen + Hangul Jamo U+1160-11FF + ZWSP + skin-tone modifiers, `WCWidth.java:126-259`); `wide[]` includes CJK, Hangul, and BMP emoji-presentation ranges (`WCWidth.java:266-382`).
- **Zero-width / combining:** in the `combining` table → width 0. **Wide CJK:** `wide` table → 2.
- **Emoji / ZWJ / flags / skin-tones:** grapheme-cluster aware. `charCountForGraphemeCluster` uses JDK 21+ `BreakIterator.getCharacterInstance()` (full UAX #29) when `HAS_JDK_GRAPHEME_SUPPORT` (`WCWidth.java:67,413-418,550-570`), else a hand-rolled legacy scanner for ZWJ/regional-indicator/VS/combining (`WCWidth.java:577-615`). Variation selectors override width: VS16 `U+FE0F`→2, VS15 `U+FE0E`→1 (`WCWidth.java:630-665`). Terminals that don't group clusters get summed per-codepoint widths, with skin-tone/regional-indicator forced to 2 (`wcwidthUngrouped`, `WCWidth.java:809-828`). **Limitation to note:** correctness of ZWJ-emoji width depends on the *terminal* actually grouping them; JLine consults `AbstractTerminal.isClusterGrouped` (`WCWidth.java:837-842`) — so width can be wrong on terminals that disagree with the JDK's segmentation. Also, per-codepoint `wcwidth` still classifies whole emoji code points as wide only if in the `wide` table; older JDKs (<21) fall back to heuristics.
- **`AttributedString` column length carrying styles:** `AttributedCharSequence.columnLength(Terminal,bi,iter,start,end)` (`AttributedCharSequence.java:967-989`) walks display units, skipping hidden chars (style bit `F_HIDDEN`) as width 0, using the same `WCWidth.charCountForDisplay`/`wcwidthForDisplay`. Styles live in a parallel `long[] styleBuffer` (`AttributedCharSequence.java:834`); width computation ignores styles entirely (they cost 0 columns).
- **Hard-wrap math:** `columnSplitLength` (`AttributedCharSequence.java:1110-1134`) accumulates display width and cuts a new line when `(col += w) > columns`, also breaking on `\n`. `Display.resize` re-wraps `oldLines` through this on size change (`Display.java:257-261`). `columnSubSequence` (`AttributedCharSequence.java:1023-1049`) extracts a column range (used by `Status` to truncate + ellipsis).

---

## 5. Window size

| Provider | Mechanism | Cite |
|----------|-----------|------|
| FFM | `ioctl(fd, TIOCGWINSZ, &winsize)`, reads `ws_col`/`ws_row` | `CLibrary.java:400-408`, struct `46-92`, downcall `277-281` |
| FFM (const) | `TIOCGWINSZ` value computed per-OS/arch | `CLibrary.java:499-530` (Linux `0x5413`, mac/BSD/AIX `0x40087468`, mips/ppc/sparc `0x40087468`) |
| JNI | same `ioctl` via `libjlinenative` (`JniNativePty`) | `native/src/main/native/clibrary.c` |
| exec | parse `stty -a` output (regex for `columns`/`rows`) | `ExecPty.doGetSize`→`doGetInt` `ExecPty.java:351-366`, config via `stty -a` `ExecPty.java:259-263` |
| Windows | `GetConsoleScreenBufferInfo`, size from `srWindow` (`right-left+1`, `bottom-top+1`) | **[agent]** `NativeWinSysTerminal.java:258-264`, `Kernel32.java:751-757,870-876` |

- **Resize is SIGWINCH-driven, not polled.** On POSIX, providers register a WINCH handler (`AbstractUnixSysTerminal` constructor loops all `Signal.values()`, `AbstractUnixSysTerminal.java:123-136`); the handler `raise(Signal.WINCH)` propagates to `LineReaderImpl.handleSignal` which re-reads size and resizes the `Display` (**[agent]** `LineReaderImpl.java:1298-1322`). On **Windows there is no SIGWINCH** — `ENABLE_WINDOW_INPUT` makes the console deliver `WINDOW_BUFFER_SIZE_EVENT`, and a pump thread synthesizes `raise(Signal.WINCH)` (**[agent]** `NativeWinSysTerminal.java:294-295`, pump `AbstractWindowsTerminal.java:605-628`). Size is read on demand (`getSize()` calls `pty.getSize()`, `AbstractPosixTerminal.java:112-119`), not cached across frames.
- **Fallback chain:** `env COLUMNS`/`LINES` are **not** consulted by the core (grep found no use). If no provider yields a TTY, `TerminalBuilder` builds a `DumbTerminal` whose size is `Size.of(0,0)` (`DumbTerminal.java:180`), which `Display` treats as 1 row × unbounded columns (`Display.java:248-251`). A caller may inject a size via `TerminalBuilder.size(Size)` (`TerminalBuilder.java:729`).

---

## 6. Repaint cadence — event-driven

- **No timer, no debounce.** Rendering is triggered per key event. In `LineReaderImpl`'s read loop, after each `readBinding(...)` and widget dispatch it calls `redisplay()` (**[agent]** `LineReaderImpl.java:783-785`, initial paint `709-710`). `redisplay(boolean)` acquires the `ReentrantLock` and ends in `display.update(...)` (**[agent]** `LineReaderImpl.java:4234-4381`, update calls at `4297`/`4379`).
- Redisplay is also invoked reactively on WINCH/CONT (§5) and from ~15 action sites; none is periodic.
- The only timeouts anywhere in the loop are input-side: `PumpThread` idle 60 s, per-char `reader.read(100L)`, and the 1000 ms ambiguity peek (§10). Coalescing of rapid signals happens at the signal layer (`pendingSignals` flag collapses duplicates, `FfmSignalHandler.java:378-383`), not in the painter.

---

## 7. Cursor & terminal restore

- **Cursor hide/show:** capabilities `civis`/`cnorm` exist; `Display` itself does not hide the cursor during paint (it relies on synchronized-update mode-2026 to avoid flicker instead). Hiding is available to callers via `puts(Capability.cursor_invisible/cursor_normal)`.
- **Raw mode entry (POSIX):** `AbstractTerminal.enterRawMode` (`AbstractTerminal.java:252-265`) clones current `Attributes`, clears local flags `ICANON, ECHO, IEXTEN, ISIG`, clears input flags `IXON, ICRNL, INLCR`, sets `VMIN=1, VTIME=0`, then `setAttributes` → `tcsetattr(fd, TCSANOW, …)` (FFM `CLibrary.setAttributes`, `CLibrary.java:431-438`). The termios struct is built per-OS (`CLibrary.termios`, `CLibrary.java:97-262`) with `TermiosMapping.forCurrentPlatform()` translating JLine `Attributes`↔native flag bits.
- **Raw mode (Windows):** no termios — `updateConsoleMode` rebuilds the console-input mode mask from `Attributes` (`AbstractWindowsTerminal.java:276-293`, spot-verified): base `ENABLE_WINDOW_INPUT`; `ISIG`→`ENABLE_PROCESSED_INPUT`; `ECHO`→`ENABLE_ECHO_INPUT`; `ICANON`→`ENABLE_LINE_INPUT`; mouse adds `ENABLE_MOUSE_INPUT|ENABLE_EXTENDED_FLAGS` (extended flag disables Quick-Edit). Output VTP bit `ENABLE_VIRTUAL_TERMINAL_PROCESSING=0x0004` set via `enableVtp` (**[agent]** `NativeWinSysTerminal.java:149-151`).
- **Restore paths:**
  - *Normal close:* `AbstractUnixSysTerminal.doClose` restores `originalAttributes` and unregisters native signal handlers (`AbstractUnixSysTerminal.java:238-264`); `AbstractPosixTerminal.doClose` also restores + `pty.close()` (`AbstractPosixTerminal.java:130-134`).
  - *Crash / JVM exit:* every system terminal registers a `ShutdownHooks` task in its constructor — `ShutdownHooks.add(closer)` (`AbstractUnixSysTerminal.java:138-139`), removed on explicit close (`:241`). So an un-closed terminal is still restored at JVM shutdown.
  - *Interrupt:* SIGINT is delivered to the registered handler; the terminal is not auto-restored on SIGINT unless the app closes it — the shutdown hook is the safety net.
  - **Leak to note:** if the JVM is `kill -9`'d, no hook runs and the terminal is left in raw mode (unavoidable without an external `stty sane`). `Status.close()` (`Status.java:101-108`) resets the scroll region on close — if a `Status` user skips `close()`, the DECSTBM region persists.

---

## 8. Flicker & cleanliness techniques (enumerated)

1. **Minimal per-line diff** — only changed cells are rewritten (`DiffHelper` + `Display.update`, §2).
2. **Inter-frame scroll optimization** — `dl`/`il` to shift blocks instead of repainting (`Display.java:475-529`); note it can *cause* flicker on some terminals, so it's toggleable via `setScrollOptimization` (`Display.java:220-222`) and is force-guarded by mode-2026.
3. **Synchronized output (mode 2026)** — `\033[?2026h`…`\033[?2026l` wraps each full-screen frame so the terminal renders atomically (`Display.java:126-127,452-454,761-766`). This is the single most modern anti-flicker device here. `synchronized-update-2026: yes`.
4. **Byte-mode output buffering + single flush** — accumulate the whole frame in `ByteArrayBuilder`, then one `write`+`flush` (`Display.java:769-788`).
5. **Style-state carry-across** — `ansiColorState[2]` avoids re-emitting SGR when unchanged; `ensureDefaultAnsiStyle` emits `\e[0m`/alt-charset-off only when needed before blanks/`clr_eol` (`Display.java:1124-1134`).
6. **Cost-based single-vs-multi capability choice** — `perform` picks `cuu1×n` vs `\e[nA` by measured byte cost (`Display.java:826-849`).
7. **Right-margin / delayed-wrap handling** — avoids spurious scrolling from writing into the last column (`Display.java:722-752`).
8. **Stable ordering** — top-to-bottom line loop with a single final cursor move to target (`Display.java:754-756`).
9. Cursor-hide is *available* but not used by `Display`; a clean-room renderer may add `civis`/`cnorm` around frames for terminals lacking mode-2026.

---

## 9. Pinned lines + scrolling logs (`org.jline.utils.Status`)

`Status` is JLine's pinned-bottom-status-bar mechanism, and it is the clearest worked example of "fixed footer + scrolling content above it."

- **Support gate:** requires `change_scroll_region` (`csr`), `save_cursor` (`sc`), `restore_cursor` (`rc`), and `cursor_address` (`cup`) (`Status.java:84-88`). If any is missing, `Status` is a no-op.
- **Mechanism = scroll region (DECSTBM).** It reserves the bottom N rows by shrinking the terminal's scroll region to `[0, rows-1-N]`, so normal output scrolls only within the top region and the status band is never scrolled. `update` recomputes `newScrollRegion = rows-1-lines.size()` and, when growing, save-cursor → move into region → emit `change_scroll_region 0,newScrollRegion` → restore-cursor (`Status.java:253-274`). `resize` re-establishes the region because terminals reset DECSTBM to full-screen on resize (`Status.java:128-188`, esp. `181-185`).
- **The status painter is a `Display` subclass** — `MovingCursorDisplay extends Display` (`Status.java:347-386`). Before any cursor move it does `save_cursor` + `cursor_address(firstLine,0)` (`initCursor`, `Status.java:379-385`); after painting it emits `restore_cursor` (`Status.java:359-363`) so the user's editing cursor is untouched. This is the "redraw decorator hooked around writes" pattern.
- **Line fitting:** each status line is truncated with an inverse `…` ellipsis or right-padded with spaces to exactly `columns` (`Status.java:237-251`).
- Answer to the sub-question: coexistence is via **scroll region**, not by intercepting every write — the terminal itself keeps scrolling output out of the reserved band; `Status` only repaints the band and juggles the cursor.

---

## 10. Interactive input **[agent + spot-verified]**

- **Raw keyboard read:** POSIX raw mode (§7) + a non-blocking reader. `NonBlockingReaderImpl` owns a daemon `PumpThread` (`NonBlockingReaderImpl.java:38,52`) that does the blocking `in.read()` while `read(timeout, isPeek)` waits with `wait(t.timeout())` and returns `READ_EXPIRED=-2` on timeout (`NonBlockingReaderImpl.java:112-186`; constants `NonBlockingReader.java:79-80`).
- **KeyMap trie:** 128-slot direct-mapped arrays with nested `KeyMap`s; a bound prefix that is also the start of a longer sequence is stored as `anotherKey` (`KeyMap.java:48,60-64,668-692`). Arrow/function keys are bound to their escape sequences (e.g. `kcuu1=\EOA`).
- **Ambiguity resolution (the crux):** `DEFAULT_AMBIGUOUS_TIMEOUT = 1000L` ms (`KeyMap.java:58`, verified). In `BindingReader.readBinding`, when the current buffer is a complete binding that *could* be extended, it peeks with the timeout; if another char arrives in time it discards the short match and keeps reading (`BindingReader.java:131-136`, verified):
  ```java
  long ambiguousTimeout = keys.getAmbiguousTimeout();
  if (ambiguousTimeout > 0 && peekCharacter(ambiguousTimeout) != NonBlockingReader.READ_EXPIRED) { o = null; }
  ```
  This is how a lone `ESC` is distinguished from the start of `ESC [ A`.
- **Input/render coexistence:** single-threaded — the read loop reads a binding, runs the widget, then `redisplay()` (§6), all under `LineReaderImpl`'s `ReentrantLock` (`LineReaderImpl.java:271`); only the signal handler runs on another thread and it grabs the same monitor/lock.

---

## 11. Non-TTY fallback & color capability

- **TTY detection:** POSIX `isatty(fd)` — FFM `CLibrary.isTty` returns `isatty(fd)==1` (`CLibrary.java:440-446`, downcall `283-284`); the provider probes it via `prov.isSystemStream(...)` during selection (`TerminalBuilder.java:1304`). Windows uses `GetConsoleMode` succeeding on the std handle (**[agent]** `NativeWinSysTerminal.java:153-171`).
- **Dumb fallback:** when no provider yields a system terminal, `TerminalBuilder` builds a `DumbTerminal` of type `dumb` or, if color is wanted, `dumb-color` (`TerminalBuilder.java:1013-1033,1084-1090`). `dumb.caps` has `am, cols#80` and essentially no cursor movement (`dumb.caps`); `dumb-color.caps` adds `colors#256`. `AttributedCharSequence.toAnsi` returns plain text for `TYPE_DUMB` (`AttributedCharSequence.java:160-163`), and `Display.update` strips ANSI when `max_colors` < 8 (`Display.java:466-472`).
- **Color capability:** primary source is terminfo `max_colors` (`colors#…`). **Truecolor upgrade** in `AbstractTerminal.detectTrueColorSupport` (`AbstractTerminal.java:354-368`): if `COLORTERM` contains `truecolor` or `24bit`, or `TERM` contains `-direct`, set `max_colors = TRUE_COLORS (0x1000000)`. Cygwin `xterm` is bumped to `xterm-256color` (`TerminalBuilder.java:928-934`). SGR emission picks basic 30-37/90-97, 256-color `38;5;n`, or truecolor `38;2;r;g;b` based on `colors` and `ForceMode` (`AttributedCharSequence.java:455-527`).
- **Gaps to note:** `NO_COLOR` and `CLICOLOR`/`CLICOLOR_FORCE` are **not** honored anywhere (grep confirms). Only `COLORTERM` is read. A clean-room lib targeting modern CLI etiquette should add `NO_COLOR`.

---

## 12. Abstraction seam & concurrency

- **SPI:** `org.jline.terminal.spi.TerminalProvider`, loaded by name via `TerminalProvider.load(name)` (`TerminalBuilder.java:1303`). Providers: `ffm`, `jni`, `exec`, plus `dumb`.
- **Selection priority:** `PROP_PROVIDERS_DEFAULT = "ffm,jni,exec"` (`TerminalBuilder.java:144-145`). `getProviders` loads each enabled provider then **sorts by the order string** (`TerminalBuilder.java:1269-1288`); the build loop then tries providers in order until one returns a terminal (`TerminalBuilder.java:1055-1073`), falling back to dumb. System properties: `org.jline.terminal.provider` (force one), `org.jline.terminal.providers` (order), `org.jline.terminal.{ffm,jni,exec,dumb}` (enable/disable), `org.jline.terminal.dumb`/`.dumb.color` (`TerminalBuilder.java:138-150`).
- **What each provider needs natively:** ffm → JDK 22+ FFM (no native lib, uses libc via `Linker.nativeLinker()`); jni → bundled `libjlinenative.{so,dylib,dll}` (`native/src/main/resources/...`); exec → only `/bin/sh`, `stty`, `tty`, `infocmp` on `PATH`.
- **Pure-Java exec fallback (`ExecPty`):** shells out. `stty -a` to read attrs+size (`ExecPty.java:259-263`); `stty <flags…>` to set attrs (`getFlagsToSet` builds token list, `ExecPty.java:180-251`); `stty columns C rows R` to set size (`ExecPty.java:368-389`); `tty` to find the device (`ExecPty.java:90`); `infocmp` for caps (`InfoCmp.java:613,626`). It parses `stty -a` output with regexes (`doGetAttr`/`doGetFlag`/`doGetInt`, `ExecPty.java:265-366`). Slow (process per operation) but requires no native code.
- **Threading / who owns writes:** `Display` is explicitly **not thread-safe** (`Display.java:60-79`) — a single painter thread must own it. `LineReaderImpl` serializes the read loop and redisplay under one `ReentrantLock` (`LineReaderImpl.java:271`); the WINCH/CONT signal handler is the only other thread and synchronizes with it. `Status` is driven from the same thread as its `Display`.
- **SIGWINCH delivery to Java (three routes):**
  1. **FFM:** direct POSIX `sigaction()` via a downcall + an *upcall stub* as the native handler (`FfmSignalHandler.java:169-192`). The handler does only an atomic `pendingSignals.set(signum,1)` (`FfmSignalHandler.java:378-383`); a daemon dispatcher thread ("JLine-signal-dispatcher") polls at 1 ms and runs the Java `Runnable` off signal context (`FfmSignalHandler.java:455-489`). Uses `SA_RESTART` (per-OS value) to auto-restart interrupted syscalls (`FfmSignalHandler.java:270`). Per-OS signal numbers + struct layouts hardcoded (`FfmSignalHandler.java:98-162`).
  2. **JNI:** native handler in `libjlinenative`.
  3. **Legacy/`Signals`:** reflection over `sun.misc.Signal`/`sun.misc.SignalHandler` via a `Proxy` (`Signals.java:87-152`).
  - **Re-registration / threading caveats the code itself flags:** the FFM upcall is *not formally async-signal-safe* (`FfmSignalHandler.java:35-39` doc) — mitigated by doing only an atomic store. Handler bookkeeping (`handlers` map, dispatcher start/stop) is `synchronized`; multiple rapid signals coalesce (POSIX semantics). Re-registration is handled by `AbstractUnixSysTerminal.handle` unregistering the old native handler before installing a new one (`AbstractUnixSysTerminal.java:143-162`).

---

## (a) Essential classes/types

| Name | Role | File |
|------|------|------|
| `Display` | The diff-based in-place renderer (old/new line diff, cursor motion, scroll opt, mode-2026) | `terminal/…/utils/Display.java` |
| `DiffHelper` | Alloc-free common prefix/suffix diff of two `AttributedString`s (hidden-range aware) | `terminal/…/utils/DiffHelper.java` |
| `Curses` | terminfo parameterized-capability interpreter (`tputs`, `%p1%d`, if-then-else, arithmetic) | `terminal/…/utils/Curses.java` |
| `InfoCmp` | Capability DB: embedded `.caps` loader + external `infocmp` + parser | `terminal/…/utils/InfoCmp.java` |
| `WCWidth` | Unicode-16 display-width + grapheme-cluster segmentation | `terminal/…/utils/WCWidth.java` |
| `AttributedString` / `…Builder` / `AttributedCharSequence` | Styled text, `columnLength`, `columnSplitLength`, `toAnsi(Bytes)` SGR emission | `terminal/…/utils/Attributed*.java` |
| `AttributedStyle` | Packed `long` style bits (color/decoration/hidden) | `terminal/…/utils/AttributedStyle.java` |
| `ByteArrayBuilder` | Zero-alloc UTF-8/ASCII/CSI byte buffer for byte-mode output | `terminal/…/utils/ByteArrayBuilder.java` |
| `Status` | Pinned bottom status bar via DECSTBM scroll region + `MovingCursorDisplay` | `terminal/…/utils/Status.java` |
| `Signals` | Legacy `sun.misc.Signal` reflection handler | `terminal/…/utils/Signals.java` |
| `TerminalBuilder` | Provider discovery/selection, dumb fallback, color/type resolution | `terminal/…/terminal/TerminalBuilder.java` |
| `AbstractTerminal` / `AbstractPosixTerminal` / `AbstractUnixSysTerminal` | `enterRawMode`, signal wiring, shutdown hook, close/restore | `terminal/…/terminal/impl/Abstract*.java` |
| `CLibrary` (FFM) | libc downcalls: `ioctl`, `isatty`, `tcgetattr/tcsetattr`, `openpty`, `ttyname_r` | `terminal-ffm/…/ffm/CLibrary.java` |
| `FfmSignalHandler` | `sigaction()` + upcall stub + dispatcher thread | `terminal-ffm/…/ffm/FfmSignalHandler.java` |
| `Kernel32` / `NativeWinSysTerminal` (FFM) | Windows console API downcalls, size, VTP, input pump | `terminal-ffm/…/ffm/*.java` |
| `ExecPty` / `ExecTerminalProvider` | Pure-Java `stty`/`tty`/`infocmp` fallback | `terminal/…/terminal/impl/exec/*.java` |
| `NonBlockingReaderImpl` / `PumpThread` | Timeout reads via background pump | `terminal/…/utils/NonBlocking*.java` |
| `KeyMap` / `BindingReader` | Escape-sequence trie + ambiguity-timeout decoding | `reader/…/keymap/*.java` |
| `LineReaderImpl` | Main consumer: owns `Display`, drives event-driven redisplay | `reader/…/reader/impl/LineReaderImpl.java` |

## (b) Top design decisions worth reproducing

1. **Per-line prefix/suffix diff over an in-memory old/new model** — cheap, allocation-free, and covers 90% of live-UI cases (progress, spinners, editing). This is the heart of the whole thing (`Display` + `DiffHelper`).
2. **Position arithmetic in `row*(cols+1)+col` with `col==cols` meaning "right margin"** — a clean way to represent the ambiguous last-column/pending-wrap state (`Display.java:88,957-977`).
3. **Mode-2026 synchronized update wrapping each frame** — modern, cheap, and the terminals that don't support it ignore it. Adopt this instead of alt-screen gymnastics.
4. **Byte-mode: build the whole frame in one byte buffer, flush once** — avoids `PrintWriter`/encoder overhead and partial-frame tearing.
5. **Separating "what to draw" (`AttributedString`) from "how to move the cursor" (`Display`)** — width/style logic lives in the string, motion logic in the renderer.
6. **`Status` = scroll region (DECSTBM) + cursor save/restore decorator** — the correct, terminal-native way to pin a footer while logs scroll above it.
7. **Grapheme-cluster width delegated to JDK 21+ `BreakIterator`** — lets Unicode updates ride the JDK rather than shipping tables (though JLine also ships Unicode-16 tables for `wcwidth`).

## (c) Hardest-to-port / native-dependency inventory — deciding the minimum viable native layer

The whole native surface reduces to **five operations**. For a bare-bones Java renderer, **FFM-only (JDK 22+) + an `stty`/`tty` exec fallback** is the evidence-backed minimum. Details per operation and per JLine route:

| Need | FFM route (downcall) | JNI route | exec route |
|------|----------------------|-----------|------------|
| **isatty** | `isatty(int)→int`, `FunctionDescriptor.of(JAVA_INT, JAVA_INT)`; `isTty` = `==1` | native `isatty` in `libjlinenative` | `tty` exit code / `test -t` |
| | `CLibrary.java:283-284,440-446` | `native/…/clibrary.c` | `ExecPty.java:90` |
| **winsize** | `ioctl(fd, TIOCGWINSZ, &winsize)`, `FunctionDescriptor.of(INT,INT,LONG,ADDRESS)` + `firstVariadicArg(2)`; struct = 4×`short` (`ws_row,ws_col,…`) | native `ioctl` | parse `stty -a` (`columns`/`rows` regex) |
| | `CLibrary.java:277-281,400-408,46-92`; `TIOCGWINSZ` consts `499-530` | | `ExecPty.java:259-263,351-366` |
| **termios raw mode** | `tcgetattr(fd,&t)` / `tcsetattr(fd,TCSANOW,&t)`, both `…of(INT,INT,ADDRESS)`; per-OS struct layout (mac 4×`long`+32-byte `c_cc`; linux 4×`int`+`c_line`+`c_cc`); flip `ICANON/ECHO/ISIG/IEXTEN`, `VMIN=1/VTIME=0` | native tcget/set | `stty -icanon -echo …` token list |
| | `CLibrary.java:286-293,421-438,97-262`; masks `AbstractTerminal.java:252-265` | | `ExecPty.java:180-251,265-366` |
| **SIGWINCH** | `sigaction(signum,&new,&old)`, `…of(INT,INT,ADDRESS,ADDRESS)`; upcall stub as handler + poll thread; per-OS struct + `SA_RESTART` | native handler | not available — must poll `getSize()` |
| | `FfmSignalHandler.java:169-192,250-295,455-489,98-162` | | (or reflect `sun.misc.Signal`, `Signals.java`) |
| **Windows console** | `GetStdHandle`, `GetConsoleMode`/`SetConsoleMode`, `GetConsoleScreenBufferInfo` (size from `srWindow`), `ReadConsoleInputW` (keys + `WINDOW_BUFFER_SIZE_EVENT`), `WriteConsoleW`; VTP bit `0x0004` | same via `libjlinenative` | limited/none |
| | **[agent]** `Kernel32.java:352-403`, `NativeWinSysTerminal.java:243-304`, `AbstractWindowsTerminal.java:98-109,276-293` | | |

**Recommended minimum for a clean-room renderer:**
- **POSIX:** FFM downcalls for `isatty`, `ioctl(TIOCGWINSZ)`, `tcgetattr`/`tcsetattr`, and `sigaction(SIGWINCH)`. That is ~4 downcalls + 2 small structs (`winsize`, `termios`) — the `CLibrary`/`FfmSignalHandler` files are a near-complete blueprint. No bundled native library needed.
- **Fallback (no FFM, or JDK < 22):** exec `tty`, `stty -a`, `stty <flags>` — no SIGWINCH, so poll size each frame. This is exactly `ExecPty`.
- **Windows:** you cannot avoid the console API — `GetStdHandle`/`Get|SetConsoleMode`/`GetConsoleScreenBufferInfo`/`ReadConsoleInputW`/`WriteConsoleW` via FFM. Resize arrives as a console event, not a signal. If you only target VT-capable Windows (Win10+ with VTP), you can set `ENABLE_VIRTUAL_TERMINAL_PROCESSING` once and then treat output like ANSI, shrinking the Windows-specific code to mode-setup + size + input.
- **Skip entirely for a bare-bones lib:** `openpty`/`ttyname_r` (only needed for creating *new* PTYs, not for driving the controlling terminal), JNI (FFM supersedes it), and the `sun.misc.Signal` reflection path (FFM `sigaction` is cleaner).

## (d) Summary block

```
REPO: jline3
painting-model: dirty-diff
cursor-motion: mixed          # relative default; absolute cup only in full-screen diagonal moves
repaint: event                # LineReaderImpl.redisplay() per key; no timer
size-source: ioctl            # + stty (exec fallback) + console-api (Windows); env COLUMNS/LINES NOT used
resize: SIGWINCH              # Windows: WINDOW_BUFFER_SIZE_EVENT -> synthetic WINCH; size read per-call not cached
vertical-overflow: cap+more   # Display clamps to rows; Status uses scroll-region; no alt-screen inside Display
width-measure: wcwidth        # Unicode 16.0 tables + JDK21 grapheme clusters
wide-char-correct: partial    # correct for CJK/combining; emoji/ZWJ depends on terminal grouping agreement
flicker-controls: minimal-diff, mode-2026-sync-update, byte-buffer-single-flush, scroll-optimization(toggle), style-state-carry, cost-based-capability-choice, right-margin/delayed-wrap-handling
synchronized-update-2026: yes   alt-screen: no   # smcup/rmcup exist as caps but Display never switches
pinned-lines: scroll-region   # Status via DECSTBM csr + save/restore cursor decorator
input: raw-keyboard           # raw termios + NonBlockingReader + KeyMap trie + 1000ms ambiguity timeout
non-tty-fallback: plain       # DumbTerminal (dumb / dumb-color), ANSI stripped
seam: TerminalProvider        # SPI, loaded by name
renderer-select: system-property-ordered   # org.jline.terminal.providers = "ffm,jni,exec"; first that yields a TTY wins, else dumb
concurrency: single-painter+lock   # Display not thread-safe; LineReaderImpl ReentrantLock; signal handler is the only other thread
jvm-hard-parts: isatty=FFM isatty()downcall / winsize=FFM ioctl(TIOCGWINSZ) or stty -a / termios=FFM tcget|tcsetattr + per-OS struct / SIGWINCH=FFM sigaction()+upcall-stub+poll-thread (or sun.misc.Signal reflection) / windows=FFM GetStdHandle+Get|SetConsoleMode+GetConsoleScreenBufferInfo+ReadConsoleInputW
```

---

## What I'd copy vs. avoid for a clean-room Java reimplementation

**Copy:**
- The old/new-line **prefix/suffix diff** model (`Display`+`DiffHelper`) — small, fast, correct for live UIs.
- **`row*(cols+1)+col` cursor coordinates** with an explicit right-margin sentinel.
- **Mode-2026 synchronized update** per frame; treat non-support as a no-op.
- **Byte-buffer-a-whole-frame-then-flush-once** discipline.
- **`Status`'s DECSTBM scroll-region + cursor save/restore** for pinned footers.
- **FFM `CLibrary`/`FfmSignalHandler`** as a literal template for the POSIX native layer (4 downcalls + 2 structs).
- Delegating grapheme width to JDK 21+ `BreakIterator`.

**Avoid / redo:**
- **Terminfo + `Curses.tputs`** — a full terminfo interpreter is heavy. For a bare-bones lib targeting ANSI/VT terminals, hardcode the ~15 sequences you need (`\e[nA`, `\e[K`, `\e[H`, `\e[?25l/h`, `\e[?2026h/l`, SGR) and skip the whole `.caps`/`infocmp` machinery. JLine's genericity is overkill unless you must support exotic/legacy terminals.
- **The `$<delay>` sleep in `tputs`** (`Curses.java:464-486`) — never sleep the render thread on capability delays.
- **`sun.misc.Signal` reflection** — use FFM `sigaction` (or the Windows event pump); don't ship the reflective path.
- **Bundling a JNI `.so`/`.dll`** — FFM removes the need; keep exec as the only non-FFM fallback.
- **`stty`-per-operation** performance (`ExecPty`) — acceptable only as a last-resort fallback, not a primary path.
- **Missing modern etiquette:** add `NO_COLOR`/`CLICOLOR` (JLine honors neither) and OSC-8 hyperlinks (absent) if you want them.
- The scroll optimization's `dl`/`il` block move can flicker without mode-2026; gate it the way JLine does (`setScrollOptimization`) or drop it if you always wrap frames in mode-2026.
```
```
