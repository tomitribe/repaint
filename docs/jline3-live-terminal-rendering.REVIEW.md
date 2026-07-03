# Fable review — `jline3-live-terminal-rendering.md`

Part 2 review pass per `terminal-rendering-analysis-prompt.md`, against
`jline3/` @ `d861ac72` (~v4.3.1). Every cited file opened and checked against
source. Verdicts: **CONFIRMED** / **CORRECTION** with `file:line`.

**Bottom line up front:** this report is substantially accurate — the strongest
first pass in the survey so far. All twelve sections and the native-layer
inventory (§c) verify against source, including the exact FFM descriptors,
TIOCGWINSZ constants, termios flags, and provider priority. Two corrections
matter for the Java design (the scroll-optimization gate and the
grapheme-cluster-mode gating of width measurement), one summary-block field is
wrong (`vertical-overflow`), and the report **misses JLine's most novel
subsystem entirely**: empirical capability probing — batch DECRQM with a DA1
sentinel, and an emoji cursor-displacement probe that *measures* how wide the
terminal actually rendered a test emoji. That subsystem is the direct answer to
the width problem the Docker review flagged, and it belongs in the survey.

---

## 1. Instrument items (a)–(j)

### (a) Escape codes & terminfo pipeline — CONFIRMED

- Capability resolution, not hardcoding: `Display` emits via
  `puts(Capability.…)` (e.g. `clr_eol` at `Display.java:592,698,731`);
  strings come from embedded `.caps` resources, registered lazily
  (`InfoCmp.java:716-735`), loaded from the classpath (`:707-714`), with the
  `infocmp -x <term>` shell-out guarded by `isValidTerminalName`
  (`InfoCmp.java:608-615`, pattern + CWE-88 rationale `:648-658`). ✔
- `.caps` contents as quoted: `civis=\E[?25l`, `cnorm=\E[?12l\E[?25h`,
  `csr=\E[%i%p1%d;%p2%dr`, `smcup=\E[?1049h` (`xterm-256color.caps:6-8,48`). ✔
- `Curses.doTputs` interpreter: parameter stack, `%p1..%p9`, `%{n}`,
  arithmetic/logic, `%?%t%e%;`, `%i` — all present (`Curses.java:92-500`). The
  `$<delay>` claim ("parsed and slept on") is **CONFIRMED with a quirk**: the
  comment at `Curses.java:466` says "We don't honour delays, just skip" — and
  the code then flushes and `Thread.sleep(nb)` anyway (`:480-486`). The report
  is right; the source comment is stale. The §avoid advice stands.
- Hardcoded exceptions confirmed: `SYNC_START/END` `\033[?2026h/l`
  (`Display.java:126-127`); focus `\033[?1004h/l`
  (`AbstractTerminal.java:426`); OSC 10/11 color queries
  (`AbstractPosixTerminal.java:163,175`). OSC-8 absent — repo-wide grep for
  `]8;;` in java sources: zero hits. ✔
- **Addition the report missed:** the hardcoded list is longer than stated —
  mode **2027** enable/disable `\033[?2027h/l` (`AbstractTerminal.java:968,977`)
  and the batch probe sequence `CSI ? u`, `CSI ?<mode>$p`…, `CSI c` (DA1)
  (`AbstractTerminal.java:523-540`). See §3 below.

### (b) Cursor motion — CONFIRMED

Relative by default with cost-chosen single-vs-parameterized forms
(`perform`/`cost`, `Display.java:826-849`); absolute `cup` only when
`hasCursorAddress && l0!=l1 && c0!=c1` (`Display.java:1001-1005`) and
`hasCursorAddress = fullScreen && cup != null` (`:172`). Non-fullscreen
downward motion is `\r` + `'\n'` repetition (`:1023-1027`). Right-margin
position reached only by writing the actual last-column character
(`:961-977`). All verified. `mixed` is the right call.

### (c) Painting model & rewind math — CONFIRMED, one gate correction

The old/new `List<AttributedString>` model, `row*columns1+col` coordinates
with `col==columns` as the right-margin sentinel (`Display.java:85-88,956-960`),
per-line loop clamped to `min(rows, max(old,new))` (`:533`), DiffHelper
prefix/suffix char+style-aware with hidden-range protection
(`DiffHelper.java:120-174`), insert/overwrite/delete tactics with
`INTRA_LINE_SKIP_THRESHOLD = 8` (`Display.java:136,633-705,873-914`),
`longestCommon` as an O(n²) run scan (`:932-954`) — all verified as written.

**CORRECTION (design-relevant): the scroll optimization has a gate the report
omits.** The condition is
`scrollOptimization && (fullScreen || newLines.size() >= rows) && newLines.size() == oldLines.size() && canScroll`
(`Display.java:475-478`). An *inline* display shorter than the viewport —
exactly the progress-block use case — never takes the `dl`/`il` path. So for
the bare-bones renderer, the scroll optimization is effectively a
fullscreen/viewport-filling feature and can be skipped entirely without losing
anything for inline progress UIs.

Also missed here: when grapheme-cluster mode is active, any changed line is
**fully repainted** rather than diffed (`Display.java:585-609`) — clusters can
retroactively merge as ZWJ arrives, which invalidates char-level cursor math.
A port that adopts cluster-aware width must adopt this fallback too.

### (d) Vertical + horizontal overflow — CONFIRMED, one summary-field error

Clamp at `:533`; no alt-screen inside `Display`; `Status` reserves bottom rows
via DECSTBM — all verified (see (i) below for Status details). Zero-size guard
`rows=1, columns=MAX_VALUE-1` (`Display.java:248-251`). ✔ Hard-wrap and
resize-reflow via `columnSplitLength` (`Display.java:257-261`;
`AttributedCharSequence.java:1077-1134` — the report's line numbers drift
slightly across the overloads; substance correct).

**CORRECTION (summary block):** `vertical-overflow: cap+more` is wrong —
`Display` clamps **silently**; there is no "… N more" indicator (that's
compose). Correct value: `cap (silent)`.

### (e) Display-width — CONFIRMED, with a material gating refinement

- Kuhn port, Unicode 16.0 tables from UnicodeData/EastAsianWidth/emoji-data
  (`WCWidth.java:39-49`); algorithm control→−1 / combining→0 / wide→2 / else 1
  (`:107-124`); `HAS_JDK_GRAPHEME_SUPPORT = Runtime.version().feature() >= 21`
  (`:67`); VS16→2 / VS15→1 (`:655-668`); ungrouped fallback forces skin-tone
  and regional indicators to width 2 (`wcwidthUngrouped`, `:809-828`);
  delegation to `AbstractTerminal.isClusterGrouped` (`:837-842`). All verified.
- **REFINEMENT the Java design needs:** with a terminal present,
  cluster-aware measurement happens **only when
  `terminal.getGraphemeClusterMode()` is true**
  (`WCWidth.java:683-687,703,734`); the JDK-21 path alone applies only when
  `terminal == null`. Cluster mode is enabled by emitting `\033[?2027h` when
  mode 2027 probing succeeds, or flagged native after the emoji probe
  (`AbstractTerminal.java:957-983,798-802`), and `TerminalBuilder`
  **auto-enables it at construction** when supported
  (`TerminalBuilder.java:852-868`, `PROP_GRAPHEME_CLUSTER` override). So the
  real width contract is: *measure the way the terminal told you it renders* —
  per-cluster on 2027-capable terminals, per-codepoint elsewhere. The report's
  "partial, depends on terminal agreeing with JDK segmentation" is directionally
  right but understates that JLine actively *asks* rather than hopes (§3).

### (f) Repaint cadence & coalescing — CONFIRMED

Event-driven only: `redisplay()` after each binding
(`LineReaderImpl.java:783-785`), `display.update` at `:4297,:4379`, no timer,
no debounce; signal coalescing is a single atomic pending flag
(`FfmSignalHandler.signalReceived`, coalescing comment in source). PumpThread
idle 60 s (`NonBlockingReaderImpl.java:52`), `READ_EXPIRED = -2` (`:31`). ✔

### (g) Cursor & terminal restore — CONFIRMED

`enterRawMode` clears exactly `ICANON/ECHO/IEXTEN/ISIG` (local) and
`IXON/ICRNL/INLCR` (input), `VMIN=1/VTIME=0` (`AbstractTerminal.java:252-265`
— with a comment explaining *why* VMIN=1: VTIME polling made
`FileInputStream.read()` see spurious EOF). Restore: `doClose` restores
`originalAttributes` and unregisters native handlers
(`AbstractUnixSysTerminal.java:239-264`; `AbstractPosixTerminal.java:130-134`),
`ShutdownHooks.add(closer)` in the constructor / removed on close
(`AbstractUnixSysTerminal.java:139-140,241`). `Status.close()` resets the
scroll region (`Status.java:101-108`). The `kill -9` leak note is fair. ✔
`Display` never touches `civis`/`cnorm` — confirmed by grep; hide/show is
caller-side. ✔

### (h) Anti-flicker list — CONFIRMED (all eight verified in source)

Minimal diff; `dl`/`il` scroll opt with the flicker warning and
`setScrollOptimization` toggle (`Display.java:205-222`); mode-2026 wrap in
fullscreen, in a `finally` so sync mode can't leak on exception
(`:452-454,761-767` — the finally detail is worth copying and the report
doesn't call it out); byte-mode single buffer + one write/flush (`:428-446,
769-788`); style-state carry + `ensureDefaultAnsiStyle` (`:1124-1134`);
cost-based capability choice (`:826-849`); right-margin handling
(`:722-752`). ✔

### (i) Pinned lines (`Status`) — CONFIRMED, one gate addition

DECSTBM mechanism, grow/shrink region transitions with save/restore cursor
(`Status.java:253-274`), resize re-establishing the region because terminals
reset DECSTBM on resize (`:128-188`), `MovingCursorDisplay` save/restore
decorator (`:347-386`), inverse-`…` truncate / pad-to-width (`:237-251`). ✔
**Addition:** the support gate also requires a *sane size* — both dimensions
in (0, 1000) (`Status.java:88,97-99`) — and `update` clears excess old lines
top-down before delegating (`:276-292`). The <1000 guard is a nice
defensive-sentinel catch (cf. compose's unguarded −1).

### (j) Seam & concurrency — CONFIRMED

`PROP_PROVIDERS_DEFAULT = "ffm,jni,exec"` (`TerminalBuilder.java:144-145`),
sort-by-order-string (`:1269-1288`), first-provider-wins build loop
(`:1055-1076`), probe via `prov.isSystemStream(...)` inside `checkProvider`
(`:1296-1310`). `Display` documented not-thread-safe (`Display.java:60-79`);
`LineReaderImpl` `ReentrantLock` (`:271`). FFM signal route exactly as
described: `sigaction` downcall + upcall stub (`FfmSignalHandler.java`
static init), handler does one atomic `pendingSignals.set` (source comment:
rapid signals coalesce), dispatcher daemon parks 1 ms between sweeps, per-OS
signal numbers and `SA_RESTART` (mac `0x0002`, sigwinch=28) — all verified.
ExecPty: `tty` (`ExecPty.java:90`), `stty -a` (`:259-263`),
`doGetSize`/`doGetInt` regexes (`:351-366`), `stty columns C rows R`
(`:368-389`). Windows: `updateConsoleMode` flag mapping verified
(`AbstractWindowsTerminal.java:276-294` — nit: `ISIG→ENABLE_PROCESSED_INPUT`
also requires `!nativeHandlers.isEmpty()`, `:278-280`); VTP via
`SetConsoleMode(m | ENABLE_VIRTUAL_TERMINAL_PROCESSING)`
(`NativeWinSysTerminal.java:149-151`); size from `srWindow` width/height+1
(`Kernel32.java:751-757,870-877`); `WINDOW_BUFFER_SIZE_EVENT → raise(WINCH)`
(`NativeWinSysTerminal.java:294`). `DumbTerminal` size `Size.of(0,0)`
(`DumbTerminal.java:180`). `KeyMap.DEFAULT_AMBIGUOUS_TIMEOUT = 1000L`
(`KeyMap.java:58`); ambiguity peek (`BindingReader.java:131-136`). NO_COLOR /
CLICOLOR genuinely absent (grep: only the unrelated `NO_COLOR_CHANGE`
constant); truecolor via COLORTERM/`-direct`, skipped if already ≥0x7FFF
(`AbstractTerminal.java:354-368`). ✔

---

## 2. Missed by the first pass

1. **The empirical capability-probing subsystem — the biggest omission, and
   the most valuable thing in the repo for our survey.** JLine does not only
   read terminfo; it *interrogates the terminal*:
   - **Batch DECRQM probe**: one write of
     `CSI ? u` (Kitty keyboard) + `CSI ?2026$p` + `CSI ?2027$p` + `CSI ?2048$p`
     + `CSI c` (DA1 sentinel) — DA1 is near-universal, so its response fences
     the read: DA1-without-DECRPM ⇒ terminal doesn't speak DECRQM, mark all
     NOT_SUPPORTED (`AbstractTerminal.java:507-540`, `ensureModesProbed`
     `:464-506`, thread-safe, probed once). Terminal.app is blacklisted by
     `TERM_PROGRAM` because its parser leaks the `p` as visible text
     (`:477-486`).
   - **Emoji cursor-displacement probe**: when 2027 is unsupported, JLine
     writes 🇫🇷 (regional-indicator pair) and 👩‍🔬 (ZWJ sequence), issues
     DSR/CPR (`user7`/`user6` capabilities), and checks whether the cursor
     advanced exactly 2 columns — per category
     (`AbstractTerminal.java:763-802`; `groupsRegionalIndicators` /
     `groupsZwjSequences` feed `isClusterGrouped` `:672-692`). **It measures
     the terminal's actual rendering instead of trusting any table.** This
     dissolves the "terminal disagrees with your wcwidth" problem the Docker
     review §7 could only warn about — and it's cheap: two writes and two
     reads at startup, behind a probe timeout (`PROP_PROBE_TIMEOUT`, 200 ms
     default) and raw-attr setup with drain (`:729-751`).
   - The probe results flow into `TerminalBuilder`'s auto-enable of cluster
     mode (`TerminalBuilder.java:852-868`) and into Display's
     full-line-repaint fallback (`Display.java:585-609`).
2. **The 2026 probe is dead capability**: `Terminal.getModeSupport` exposes
   `SYNCHRONIZED_OUTPUT(2026)` (`Terminal.java:1332-1333`), but no internal
   consumer exists — `Display` emits the 2026 wrapper blind in fullscreen,
   relying on unsupporting terminals ignoring it (`Display.java:125-127`).
   Emit-blind is thus the field-proven pattern; the probe is available if a
   port ever wants sync-update for *inline* frames where a stray unknown
   sequence would be riskier.
3. **`getBufferSize()` as a distinct dimension** — Windows buffer vs window
   width drives disabling wrap-reliance (`Display.java:266-273`;
   `NativeWinSysTerminal.getBufferSize` `:268-274`). A port that models only
   one "width" will mis-wrap on Windows wide-buffer consoles.
4. **Sync-mode leak protection**: SYNC_END in `finally`
   (`Display.java:761-767`) — same discipline our SURVEY "restore is
   first-class" decision calls for, applied to a *mode*, not just
   cursor/termios.
5. Small ones: `Status` requires size within (0,1000) (`Status.java:97-99`);
   Windows `ISIG` mapping conditional on registered handlers
   (`AbstractWindowsTerminal.java:278-280`); the stale "we don't honour
   delays" comment above code that sleeps (`Curses.java:466,480-486`).

---

## 3. Verdict

**Safe to build from with two amendments** — the mechanism, native-layer
inventory, and summary block are verified accurate except: treat the `dl`/`il`
scroll optimization as fullscreen-only (gate at `Display.java:475-478`, skip it
for a bare-bones inline renderer), and treat grapheme-cluster width as
*probe-gated* (mode 2027 / emoji-CPR), not JDK-gated. The missed
capability-probing subsystem doesn't invalidate the report — it adds the
survey's most important new technique.

## 4. Corrected summary-block fields

```
vertical-overflow: cap (silent)   # Display clamps to rows with NO "N more" indicator; Status = scroll-region
wide-char-correct: partial        # per-cluster ONLY when mode 2027 / emoji-CPR probe says the terminal groups;
                                  # per-codepoint otherwise (WCWidth.java:683-687) — probe-gated, not JDK-gated
flicker-controls: minimal-diff, mode-2026-sync(fullscreen, emitted blind, END in finally),
                  byte-buffer-single-flush, scroll-opt(fullscreen-or-viewport-filling only, toggleable),
                  style-state-carry, cost-based-capability-choice, right-margin/delayed-wrap-handling
```
All other fields CONFIRMED as reported.
