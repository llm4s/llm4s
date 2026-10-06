package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Rejects text that contains a word from a fixed word list.
 *
 * Works as both an [[InputGuardrail]] and an [[OutputGuardrail]]: the same instance can be passed to either
 * side of an agent run. The check is rule-based, fast and deterministic, and makes no network call.
 *
 * The built-in list is deliberately minimal (two placeholder words, `badword` and `inappropriate`), so a real
 * deployment supplies its own words through `customBadWords`, or uses a moderation service or a model-based
 * guardrail for context-aware filtering.
 *
 * Matching rules, which decide what the filter does and does not catch:
 *  - The text is split on whitespace and each token is compared with the word list as a whole. `badwords` does
 *    not match `badword`, and a token with punctuation attached (`badword!`, `"badword"`) does not match
 *    either.
 *  - Entries are single words. An entry that contains whitespace (a phrase) can never match, because tokens
 *    never contain whitespace.
 *  - By default the comparison ignores case, for the built-in words and for `customBadWords` alike. With
 *    `caseSensitive = true` text and entries are compared exactly, so the lower-case built-in words no longer
 *    match `BADWORD`. Case is folded with `toLowerCase` in the JVM's default locale, so under a Turkish
 *    default locale an upper-case `I` folds to the dotless `ı` and does not match an entry spelled with `i`.
 *
 * On a match `validate` returns a [[org.llm4s.error.ValidationError]] for the field `input` (whichever side the
 * filter is used on) whose detail is `Input contains inappropriate content`. The detail never names the word
 * that matched.
 *
 * @example
 * {{{
 * import org.llm4s.agent.guardrails.builtin.ProfanityFilter
 *
 * // built-in list only
 * val filter = ProfanityFilter()
 *
 * // your own words, in addition to the built-in ones
 * val strict = ProfanityFilter.withCustomWords(Set("heck", "darn"))
 *
 * strict.validate("well, heck")  // Left(ValidationError): the token "heck" is in the list
 * strict.validate("well, heck!") // Right("well, heck!"): "heck!" is a different token
 *
 * agent.run(query, tools, inputGuardrails = Seq(strict), outputGuardrails = Seq(strict))
 * }}}
 *
 * @param customBadWords Words to reject in addition to the built-in list; empty by default. Single words only
 *                       (see the matching rules above).
 * @param caseSensitive  `false` (the default) compares without regard to case; `true` compares exactly.
 */
class ProfanityFilter(
  customBadWords: Set[String] = Set.empty,
  caseSensitive: Boolean = false
) extends InputGuardrail
    with OutputGuardrail {

  // Default bad words list (basic example - expand for production)
  private val defaultBadWords: Set[String] = Set(
    // This is intentionally minimal for example purposes
    // In production, use a comprehensive profanity list or external API
    "badword",
    "inappropriate"
  )

  private val badWords: Set[String] = {
    val combined = defaultBadWords ++ customBadWords
    if (caseSensitive) combined else combined.map(_.toLowerCase)
  }

  /**
   * Check `value` against the word list.
   *
   * @param value the text to check; empty text is valid
   * @return `Right(value)` unchanged when no whitespace-separated token is in the word list, otherwise
   *         `Left` with a [[org.llm4s.error.ValidationError]] for the field `input` that does not name the
   *         matching word
   */
  def validate(value: String): Result[String] = {
    val checkValue = if (caseSensitive) value else value.toLowerCase
    val words      = checkValue.split("\\s+")

    val foundBadWords = words.filter(badWords.contains)

    if (foundBadWords.nonEmpty) {
      Left(
        ValidationError.invalid(
          "input",
          "Input contains inappropriate content"
          // Don't reveal the specific words for security/privacy
        )
      )
    } else {
      Right(value)
    }
  }

  val name: String = "ProfanityFilter"

  override val description: Option[String] = Some(
    "Filters profanity and inappropriate content"
  )

  // Resolve conflicting transform methods from both traits
  override def transform(input: String): String = input
}

object ProfanityFilter {

  /**
   * Create a profanity filter with the built-in word list, case-insensitive.
   *
   * @return a filter equivalent to `new ProfanityFilter()`
   */
  def apply(): ProfanityFilter = new ProfanityFilter()

  /**
   * Create a case-insensitive profanity filter that also rejects `customWords`.
   *
   * @param customWords single words to reject in addition to the built-in list
   * @return a filter equivalent to `new ProfanityFilter(customBadWords = customWords)`
   */
  def withCustomWords(customWords: Set[String]): ProfanityFilter =
    new ProfanityFilter(customBadWords = customWords)

  /**
   * Create a case-sensitive profanity filter.
   *
   * Text and word-list entries are compared exactly, so the lower-case built-in words do not match their
   * upper-case spellings.
   *
   * @param customWords single words to reject in addition to the built-in list; empty by default
   * @return a filter equivalent to `new ProfanityFilter(customWords, caseSensitive = true)`
   */
  def caseSensitive(customWords: Set[String] = Set.empty): ProfanityFilter =
    new ProfanityFilter(customBadWords = customWords, caseSensitive = true)
}
