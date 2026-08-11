# 06j — resize, rebuilt on JLine

**The adoption experiment.** Same three commands as [`06-resize`](../06-resize/),
same behavior, same seam — but the plumbing handed to the incumbent
(`org.jline:jline`, whose internals are exactly what `docs/jline3-*.md`
surveyed). The module exists to answer one question by direct comparison:
**what drops when you adopt, and what survives because it was never plumbing?**

```
diff -r 06-resize/src 06-resize-jline/src     # the chapter, as a diff
```

## The ledger

| | hand-rolled (06) | on JLine (06j) |
|---|---|---|
| main source | 844 lines | 465 lines |
| dependencies | none | `jline-terminal` + `jline-terminal-ffm` |
| shaded binary | 0.9 MB | 1.5 MB |
| build JDK | **22+ required** (FFM) | **17** — FFM arrives at runtime |
| crest descriptor | broken by v66 bytecode → hand-written `Loader` | works; javadoc-as-help returns |

(Dependency hygiene note: the first cut pulled the full `org.jline:jline`
bundle — reader, styling, builtins — and weighed 2.6 MB. Charging adoption
only for what 06j actually uses, the terminal modules, saves a megabyte.
Measure the dependency you need, not the one that's easiest to name.)

**Dropped** (JLine provides it):

- `Libc.java` (170 lines) + `Terminal.java` + `Size.java` → `TerminalBuilder`
  + `Terminal.handle(Signal.WINCH, …)`. And not merely replaced — *upgraded*:
  JLine tries its providers in order (ffm → jni → exec) at **runtime**, so
  this Java-17 binary gets FFM syscalls under Java 22+ and the `stty`
  fallback under 17. Run `probe` under both JDKs and watch the terminal
  class change. That's the multi-jar provider architecture from the design
  discussion, delivered by the incumbent. Windows comes with it.
- `Width.java`'s 130-line table → a 29-line adapter over
  `AttributedString.columnLength()` (same wcwidth, Unicode 16, that the
  survey reviewed).
- `frame()`, the `numLines` accounting, the wipe loop, every hardcoded
  escape string → `org.jline.utils.Display`, which resolves sequences
  through terminfo capabilities and — bonus — **diffs per line**, writing
  only what changed. Chapter 08's mechanism arrives for free.

**Survived, byte-for-byte identical** (it was design, not plumbing):

- the seam — `Renderer`, `Job`, `Decor`
- store-latest + fps ticker + dirty-skip coalescing — JLine is event-driven
  and has no opinion about frame rate; the two-rates split is ours
- the height clamp — "… N more" is *policy*; JLine's `Display` clamps
  silently (survey-verified, `Display.java:533`)
- lifetime cursor hide + the shutdown hook — chapter 05's field-tested calls

**Traded away** (the honest column):

- `?2026` synchronized update: JLine's `Display` emits it only in fullscreen
  mode; our inline frames lose it (`Display.java:452`). On a 2026-capable
  terminal, 06 paints more atomically than 06j.
- byte-golden tests: `frame()` was a pure function; `Display` writes to a
  terminal. `FrameTest` has no equivalent here (JLine itself tests via
  `LineDisciplineTerminal` — heavier machinery). `ClampTest` survives,
  because the clamp survived.
- a layer of understanding: when 06 misbehaves, the bug is in this repo;
  when 06j misbehaves, the bug is in a dependency's diff engine. Chapters
  01–06 exist so that difference is a trade you make knowingly.
- lifecycle leniency: 06's shutdown hook pokes a `PrintStream`, which
  shrugs; 06j's pokes a `Terminal`, which throws once closed
  (field-found: `IllegalStateException: Terminal has been closed`).
  Adopted objects police their lifecycles — so the restore hook is
  deregistered on orderly close, which is JLine's own internal pattern
  (`ShutdownHooks.add` in the constructor, `.remove` in `doClose`).

## The conclusion the numbers point at

Roughly 380 lines — the syscalls, the width table, the frame assembly —
were *commodity*: the incumbent does them as well or better, plus Windows,
plus terminfo, plus runtime provider selection. What it cannot provide is
the part that made chapters 03 and 05 worth writing: the seam, the
two-rates coalescing, the clamp policy, the cursor discipline. Those
survived unchanged because they're the library's actual design — which is
also the survey's conclusion, arrived at now by construction.
