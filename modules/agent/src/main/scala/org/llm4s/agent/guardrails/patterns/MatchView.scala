package org.llm4s.agent.guardrails.patterns

import java.text.Normalizer

/**
 * The copy of a text the PII and secret patterns match, with a map from each of its positions back to the
 * original, so a match is reported, and masked, at the original's indices (#3244).
 *
 * The copy is the original with, code point by code point:
 *  - every format character (Unicode category Cf: zero-width space, joiners, word joiner, byte-order mark, soft
 *    hyphen, bidirectional controls) and every combining mark (category M) removed, so a number split by an
 *    invisible character still matches;
 *  - every dash (category Pd: the non-breaking hyphen, en and em dashes, the fullwidth hyphen-minus) written as
 *    an ASCII `-`;
 *  - everything else in Unicode NFKC, so fullwidth digits, a fullwidth `@` or `.` and compatibility spaces read
 *    as their ASCII forms.
 *
 * Building it takes time linear in the text. Pure ASCII text is matched as it is.
 */
final private[guardrails] class MatchView private (
  val text: String,
  starts: Array[Int],
  ends: Array[Int],
  originalLength: Int
) {

  /** The original index of the copy's index `i` (`text.length` maps to the original's length). */
  def start(i: Int): Int =
    if (starts.isEmpty && text.length == originalLength) i
    else if (i >= starts.length) originalLength
    else starts(i)

  /**
   * The original end (exclusive) of a match that starts at the copy's index `begin` and ends (exclusive) at its
   * index `end`. Characters the copy dropped inside the match are part of it; those before or after it are not.
   */
  def end(begin: Int, end: Int): Int =
    if (starts.isEmpty && text.length == originalLength) end
    else if (end <= begin) start(begin)
    else ends(end - 1)
}

private[guardrails] object MatchView {

  def apply(original: String): MatchView =
    if (original.forall(_ < 0x80)) new MatchView(original, Array.emptyIntArray, Array.emptyIntArray, original.length)
    else build(original)

  private def build(original: String): MatchView = {
    val out    = new java.lang.StringBuilder(original.length)
    val starts = new scala.collection.mutable.ArrayBuilder.ofInt
    val ends   = new scala.collection.mutable.ArrayBuilder.ofInt
    starts.sizeHint(original.length)
    ends.sizeHint(original.length)
    var i = 0
    while (i < original.length) {
      val cp   = original.codePointAt(i)
      val next = i + Character.charCount(cp)
      val kind = Character.getType(cp)
      val mapped =
        if (kind == Character.FORMAT || isMark(kind)) ""
        else if (kind == Character.DASH_PUNCTUATION) "-"
        else if (cp < 0x80) original.substring(i, next)
        else Normalizer.normalize(original.substring(i, next), Normalizer.Form.NFKC)
      var j = 0
      while (j < mapped.length) {
        out.append(mapped.charAt(j))
        starts += i
        ends += next
        j += 1
      }
      i = next
    }
    new MatchView(out.toString, starts.result(), ends.result(), original.length)
  }

  private def isMark(kind: Int): Boolean =
    kind == Character.NON_SPACING_MARK || kind == Character.ENCLOSING_MARK || kind == Character.COMBINING_SPACING_MARK
}
