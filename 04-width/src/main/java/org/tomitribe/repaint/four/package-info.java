/**
 * Chapter 04 — width means cells, and Java will lie to you about it.
 *
 * Every layout decision a renderer makes — padding, alignment, truncation,
 * "does this line fit?" — needs the string's DISPLAY width: how many
 * terminal cells it occupies. Java offers three measurements and all of
 * them are wrong:
 *
 *   String.length()      UTF-16 code units — an emoji is already 2
 *   codePointCount(...)  code points — closer, but a CJK glyph is 1
 *   (bytes)              don't even
 *
 * The truth: a CJK ideograph is ONE code point and TWO cells; a combining
 * accent is a code point and ZERO cells; an emoji is often several code
 * points rendering as two cells. The only correct measure is a
 * wcwidth-style lookup, and the discipline that matters more than the
 * table: ONE width function, with every pad/truncate/align in the program
 * routed through it (docs/SURVEY.md, "Measure display width with a
 * wcwidth table"). Docker ships the correct primitive and doesn't use it
 * in the live path — that latent bug (docs/docker-live-terminal-rendering.md
 * §7) is reproduced here on purpose: `tasks --fit=chars`.
 */
package org.tomitribe.repaint.four;
