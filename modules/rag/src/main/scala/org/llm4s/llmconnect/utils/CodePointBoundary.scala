package org.llm4s.llmconnect.utils

/**
 * Cut points in a `String` that never fall inside a UTF-16 surrogate pair (#1711).
 *
 * Text is cut by UTF-16 index, so a cut between the high and the low half of an astral character (emoji, CJK
 * Extension B, ...) leaves a lone surrogate on each side. That is invalid text: an embedding API rejects it or
 * replaces it with U+FFFD, and the character is lost from both pieces.
 *
 * Every helper here returns its input unchanged unless the index sits exactly between a high and a low surrogate, so
 * text without astral characters is cut exactly as before. Lone surrogates already present in the input are left
 * alone.
 */
private[llm4s] object CodePointBoundary {

  /** True when cutting `text` at `index` would separate the two halves of a surrogate pair. */
  def splitsPair(text: String, index: Int): Boolean =
    index > 0 && index < text.length &&
      Character.isHighSurrogate(text.charAt(index - 1)) &&
      Character.isLowSurrogate(text.charAt(index))

  /** `index`, moved back by one when it would split a surrogate pair. Never larger than `index`. */
  def floor(text: String, index: Int): Int =
    if (splitsPair(text, index)) index - 1 else index

  /** `index`, moved forward by one when it would split a surrogate pair. Never smaller than `index`. */
  def ceil(text: String, index: Int): Int =
    if (splitsPair(text, index)) index + 1 else index

  /**
   * At most `n` UTF-16 units from the start of `text`, never ending on the high half of a pair: the cut moves back by
   * one instead, so the result is never longer than `n` (it can be one shorter).
   */
  def take(text: String, n: Int): String =
    if (n >= text.length) text
    else if (n <= 0) ""
    else text.substring(0, floor(text, n))
}
