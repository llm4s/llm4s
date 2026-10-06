package org.llm4s.agent.guardrails.builtin

import java.text.Normalizer
import java.util.Locale

/**
 * The normalisation the keyword- and pattern-matching guardrails apply to text before they match it, so that
 * spelling variants of a phrase cannot slip past a pattern written in plain lower-case ASCII.
 *
 * Only the copy that is matched is normalised; the guardrails always return the caller's text unchanged.
 */
private[guardrails] object MatchText {

  // Combining marks (accents, the dot of a decomposed İ) and format characters (zero-width space, zero-width
  // non-joiner and joiner, word joiner, byte-order mark, soft hyphen, bidirectional controls).
  private val Invisible = "[\\p{M}\\p{Cf}]".r

  /**
   * Normalise `text` without changing its case:
   *  1. Unicode NFKD, which maps compatibility forms to their plain equivalents (fullwidth `ＩＧＮＯＲＥ` to
   *     `IGNORE`, ligatures such as `ﬁ` to `fi`, compatibility spaces such as a non-breaking space to an ASCII
   *     space) and splits accented letters into a base letter plus combining marks;
   *  1. removes every combining mark (Unicode category M), so `ïgnöre` becomes `ignore`;
   *  1. removes every format character (Unicode category Cf), such as U+200B, U+200C, U+200D, U+2060 and
   *     U+FEFF, so a phrase split by an invisible character still matches.
   *
   * Letters that do not decompose (such as the Turkish dotless `ı`, or look-alike letters from other scripts,
   * such as Cyrillic `і`) are left as they are.
   */
  def canonical(text: String): String =
    Invisible.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFKD), "")

  /**
   * [[canonical]], then lower-cased with `Locale.ROOT` (so the JVM's default locale has no effect), with the
   * Turkish dotless `ı` mapped to `i`. `İGNORE`, `ıgnore`, `ＩＧＮＯＲＥ` and `ig<U+200B>nore` all become
   * `ignore`.
   */
  def folded(text: String): String = {
    // Lower-casing can itself produce a combining mark (Locale.ROOT folds `İ` to `i` + U+0307), so the marks
    // are stripped again afterwards; canonical has already decomposed `İ`, but stay safe for other letters.
    val lower = canonical(text).toLowerCase(Locale.ROOT).replace('\u0131', 'i')
    Invisible.replaceAllIn(Normalizer.normalize(lower, Normalizer.Form.NFKD), "")
  }
}
