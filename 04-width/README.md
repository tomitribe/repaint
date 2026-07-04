# 04 — width

**Adds:** one wcwidth-based width function, routed through every pad,
truncate, and column alignment.
**Teaches:** width means *cells*, and every measurement Java hands you —
`length()`, `codePointCount()`, bytes — is a different wrong answer.

## The starting defect

Chapter 03's `fit()` counted Java `char`s. Its task names were ASCII, so
nothing visibly broke — chars and cells happened to agree. The world isn't
ASCII:

```
./target/width tasks --fit=chars
```

CJK, Hangul, and emoji task names; the status and timer columns stagger,
because `String.format("%-14s", "デプロイ")` pads to 14 *chars* while the
terminal renders 8 *cells* of glyph. This is not a contrived bug — it is,
line for line, the latent defect `docker compose up` ships today: its live
renderer measures runes (and sometimes bytes) while the correct cell-width
primitive sits unused in the same tree (`docs/docker-live-terminal-rendering.md`
§7 — the survey's highest-stakes finding).

## The five measurements

| measure | `"café"` (NFD) | `"日本語"` | `"👍"` |
|---|---|---|---|
| bytes (UTF-8) | 6 | 9 | 4 |
| `String.length()` (UTF-16 units) | 5 | 3 | **2** |
| `codePointCount()` | 5 | 3 | 1 |
| grapheme clusters | 4 | 3 | 1 |
| **cells — what the terminal renders** | **4** | **6** | **2** |

No two columns agree. Layout arithmetic in anything but cells *will* drift —
the only question is which string triggers it first.

```
./target/width measure
```

Each sample prints padded to 16 **cells**; if our arithmetic agrees with your
terminal, the right-hand brackets form a perfect column. Below, the same rows
padded to 16 **chars** — the staggered bracket edge is chapter 03's bug made
visible. Pass your own strings to interrogate more.

## The mechanism

`Width.java` is Markus Kuhn's classic `wcwidth()`: combining/format code
points (accents, ZWJ) are 0 cells — Java's `Character.getType()` supplies
those classes for free; East-Asian wide and emoji ranges are 2 cells — those
we carry as a sorted table (real implementations generate it from
`EastAsianWidth.txt` + `emoji-data.txt`; see JLine's `WCWidth.java`,
Unicode 16); everything else is 1. Two disciplines matter more than the table:

1. **Walk by code point, never by `char`** — `charAt()`/`substring()` will
   happily split an emoji's surrogate pair in half (`Width.truncate` never
   can; compare docker byte-slicing its details field, `tty.go:513`).
2. **One function, all roads through it** (`docs/SURVEY.md`). Docker's bug
   isn't a missing primitive — it's that the live path doesn't *route*
   through the one that exists.

## Golden-string tests

Because frames are computed as pure string arithmetic before any byte reaches
a terminal, the chapter is testable with `assertEquals` — `WidthTest` pins
the CJK/combining/emoji cases and keeps the chapter-03 arithmetic around as a
failing exhibit. Compose tests its renderer the same way (golden frames in
`tty_test.go`). `mvn test` runs them.

## Honest limits (→ horizon)

Per-code-point wcwidth measures a ZWJ family emoji (👨‍👩‍👧 = man+ZWJ+woman+ZWJ+girl)
at 6 cells; terminals render it anywhere from 2 to 6. There is no
table-only answer — the terminal must be *asked* (mode 2027, JLine's
emoji-cursor-probe, bubbletea's DECRQM upgrade; `docs/SURVEY.md`,
"Probe, don't guess"). That's the probe chapter on the horizon. Same story
for East-Asian *ambiguous* glyphs like `✔`, which render 2 cells on some
CJK terminals.

## What's still broken (→ chapters 05, 06)

- **Flicker** at high fps — per-line unbuffered writes, visible cursor —
  **chapter 05**.
- **The size is still a startup snapshot**, and `stty` still measures the
  wrong fd in edge cases — FFM `ioctl` + SIGWINCH, **chapter 06**.
