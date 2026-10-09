package org.llm4s.agent.memory

import java.text.Normalizer
import java.util.Locale

/**
 * The words keyword search compares: what `InMemoryStore` splits a query and a memory into.
 *
 * It follows the SQLite stores' FTS5 index (the `unicode61` tokenizer), so the stores agree on what a word is:
 *  - a word is a run of letters and digits in any script (with the combining marks that belong to them), and
 *    anything else - spaces, punctuation, symbols - separates words, so `java?` is the word `java`;
 *  - words are compared case-insensitively, folded with `Locale.ROOT` so the result does not depend on the JVM's
 *    default locale;
 *  - Latin accents are ignored (`cafe` matches `café`), written precomposed or decomposed;
 *  - there is no minimum length and no stop-word list: `I` matches the word `I`, never the `i` inside `Berlin`.
 */
private[memory] object KeywordTokens {

  /** Separates words: anything that is not a letter, a digit or a combining mark. */
  private val Separator = "[^\\p{L}\\p{N}\\p{M}]+".r

  /** The combining accents of Latin, Greek and Cyrillic letters, which FTS5's `unicode61` removes. */
  private val Accents = "[\\u0300-\\u036f]".r

  /** The distinct words of `text`, case- and accent-folded. */
  def of(text: String): Set[String] = {
    val folded = Accents.replaceAllIn(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD), "")
    Separator.split(folded).iterator.filter(_.nonEmpty).toSet
  }
}
