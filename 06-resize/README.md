# 06 — resize

**Adds:** the real syscalls via FFM — `isatty`, `ioctl(TIOCGWINSZ)`,
`sigaction(SIGWINCH)` — plus the height clamp and live resize.
**Teaches:** the native layer is three functions; geometry stops being a
startup guess.

> Build note: this module needs a **Java 22+** JDK (FFM went final in 22);
> chapters 01–05 stay on 17. Build the whole repo with e.g.:
>
> ```
> JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn clean package
> ```
>
> A plain Java-17 build skips this module and still builds 01–05.

## The starting defects

Chapter 03's terminal knowledge was rented, not owned: two forked processes
per probe (`sh -c 'test -t 1'`, `stty size`), a size measured on *stdin's*
terminal while we paint stdout, and a snapshot taken once at startup. And
chapter 05 shipped a scrollback grave, found in the field: run `--tasks 50`
in a 30-row window and the climb clamps at the screen top while every frame
pumps sheared fragments into scrollback.

## The native layer is three functions

`Libc.java` is the entire thing — no JNI, no bundled `.dylib`, no forked
processes. FFM's `Linker` binds symbols from the libc the JVM already has
loaded:

| Call | Replaces | Notes |
|---|---|---|
| `isatty(fd)` | `sh -c 'test -t 1'` | one downcall |
| `ioctl(fd, TIOCGWINSZ, &ws)` | `stty size` | on **fd 1, the fd we draw on** — the compose bug is now structurally impossible; cheap enough to re-ask any time |
| `sigaction(SIGWINCH, …)` | nothing — 03 couldn't do this | resize becomes a **push**: an upcall stub is installed as the signal handler |

The signal handler runs in *signal context*, where almost nothing is safe —
so it does the one safe thing, a single atomic store; a daemon dispatcher
thread turns that flag into your callback. Same design and same documented
caveat as JLine's `FfmSignalHandler` (FFM upcalls aren't formally
async-signal-safe; one store is the mitigation). The magic numbers
(`TIOCGWINSZ`, `SA_RESTART`, the sigaction struct layout) differ per OS —
we carry macOS and Linux; JLine's `CLibrary` is the full per-OS table
(`docs/jline3-live-terminal-rendering.md` §c).

```
./target/resize probe          # same questions as chapter 03, syscall answers
./target/resize watch          # drag the window corner: SIGWINCH → 143x38
```

## The height clamp

The invariant, learned the hard way: **after its first frame, a
well-behaved block never scrolls the screen.** So the block never paints
more rows than the window minus one (the cursor's resting row); excess
collapses into "… N more" — compose's defense (`tty.go:325`), pinned by
`ClampTest`.

```
./target/resize tasks --tasks 50       # in any window: no shearing, no grave
```

## Live resize

`SIGWINCH → flag → next paint`: re-measure (one cheap ioctl), climb the old
block, `ESC[J` (erase from cursor to end of screen — new to the toolbox),
repaint fresh at the new size. Drag the corner mid-run: grow the window and
rows emerge from the "more" line; shrink it and they fold back in.

Honest limit: on *shrink*, terminals rewrap the block's old rows during the
resize, so the climb can land off by the rewrapped lines — a transient
artifact the next frame mostly repairs. Compose accepts the same; the only
full cures are absolute positioning or the alternate screen (horizon).

## Where the ladder stands

With this chapter the core arc is complete. What `Terminal` + `Width` +
`SmoothRenderer` + the `Renderer` seam now do — probe honestly, measure in
cells, paint atomically at frame rate, survive geometry — is the bare-bones
library the survey aimed at. Everything after this (per-line diffing, raw
input, pinned regions, capability probing) is growth, not foundation.

A second edition of this chapter could split the native layer into its own
jar behind a provider SPI — JLine ships `terminal-ffm` exactly that way —
so the exec fallback (chapter 03) and the FFM path become swappable
artifacts. Parked until wanted.
