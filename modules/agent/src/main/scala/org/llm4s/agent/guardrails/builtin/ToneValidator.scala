package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.util.matching.Regex

/**
 * Tone categories for content validation.
 *
 * Represents the detected or expected tone of text content.
 * Used by [[ToneValidator]] to enforce tone requirements on LLM output.
 */
sealed trait Tone {

  /** The tone's display name, as it appears in [[ToneValidator]]'s error and description text. */
  def name: String
}

object Tone {

  /** Business-appropriate language (e.g. "please", "thank you", "regards"). */
  case object Professional extends Tone { val name = "Professional" }

  /** Informal, relaxed language (e.g. "hey", "cool", "awesome"). */
  case object Casual extends Tone { val name = "Casual" }

  /** Warm, approachable language (e.g. "hi", "hello", "thanks"). */
  case object Friendly extends Tone { val name = "Friendly" }

  /** Academic or official language (e.g. "furthermore", "moreover", "consequently"). */
  case object Formal extends Tone { val name = "Formal" }

  /** Enthusiastic language with exclamation marks and short sentences. */
  case object Excited extends Tone { val name = "Excited" }

  /** Default tone when no specific indicators are detected. */
  case object Neutral extends Tone { val name = "Neutral" }

  /** All available tone categories. */
  val all: Set[Tone] = Set(Professional, Casual, Friendly, Formal, Excited, Neutral)
}

/**
 * Rejects LLM output whose detected [[Tone]] is not in an allowed set.
 *
 * An [[OutputGuardrail]] only: it checks what the model says, not what the user sends. Detection is a fixed
 * keyword heuristic over the lower-cased text, with no model call, so it is fast and deterministic, and it
 * is crude. For a judgement that understands context, use [[LLMToneGuardrail]] instead.
 *
 * Detection looks at the whole text, line breaks included, and takes the first rule that applies, in this
 * order:
 *  1. [[Tone.Excited]]: the text contains `!` and some sentence in it (text split on `.`, `!` and `?`) has
 *     fewer than five words. This rule wins over every keyword below; a long, calm text that merely ends in
 *     `!` is not Excited.
 *  1. [[Tone.Professional]]: contains `please`, `thank you`, `kindly`, `regards` or `sincerely`.
 *  1. [[Tone.Casual]]: contains `hey`, `cool`, `awesome`, `yeah` or `nah`.
 *  1. [[Tone.Friendly]]: contains `hi`, `hello`, `thanks` or `appreciate`.
 *  1. [[Tone.Formal]]: contains `furthermore`, `moreover`, `consequently` or `therefore`.
 *  1. [[Tone.Neutral]]: none of the above, including empty text.
 *
 * Keywords match whole words only (`pleased` is not `please`), so a text with keywords of several tones is
 * classified by the earliest rule above, not by how many keywords it has. Because the lists are short and in
 * English, text in another language, or in none of these registers, comes out Neutral; allow
 * [[Tone.Neutral]] (or use `allowAll`) if that should pass.
 *
 * On a mismatch `validate` returns a [[org.llm4s.error.ValidationError]] for the field `output`, whose detail
 * names the detected tone and the allowed ones, for example
 * `Output tone (Casual) not allowed. Allowed tones: Professional`.
 *
 * @example
 * {{{
 * import org.llm4s.agent.guardrails.builtin.{ Tone, ToneValidator }
 *
 * // ready-made sets
 * val customerFacing = ToneValidator.professionalOrFriendly
 *
 * // or choose the allowed tones yourself
 * val calm = ToneValidator(Set(Tone.Professional, Tone.Formal, Tone.Neutral))
 *
 * calm.validate("Please find the report attached.") // Right(...): Professional
 * calm.validate("Hey, that is cool.")               // Left(ValidationError): Casual is not allowed
 *
 * agent.run(query, tools, outputGuardrails = Seq(customerFacing))
 * }}}
 *
 * @param allowedTones The tones that pass. An empty set rejects everything; `Tone.all` accepts everything.
 */
class ToneValidator(allowedTones: Set[Tone]) extends OutputGuardrail {

  /**
   * Detect the tone of `value` and check it is allowed.
   *
   * @param value the output text to check; empty text is Neutral
   * @return `Right(value)` unchanged when the detected tone is in the allowed set, otherwise `Left` with a
   *         [[org.llm4s.error.ValidationError]] for the field `output` naming the detected and the allowed
   *         tones
   */
  def validate(value: String): Result[String] = {
    val detectedTone = detectTone(value)

    if (allowedTones.contains(detectedTone)) {
      Right(value)
    } else {
      Left(
        ValidationError.invalid(
          "output",
          s"Output tone (${detectedTone.name}) not allowed. Allowed tones: ${allowedTones.map(_.name).mkString(", ")}"
        )
      )
    }
  }

  /**
   * Detect the tone of text using simple keyword-based heuristics.
   *
   * This is a basic implementation. For production use:
   * - Sentiment analysis APIs (OpenAI, Google Cloud Natural Language)
   * - Custom ML models trained on tone classification
   * - More sophisticated linguistic analysis
   */
  private def detectTone(text: String): Tone = {
    val lower = text.toLowerCase

    // Check for excitement indicators (short sentences with exclamation marks)
    if (lower.contains("!") && lower.split("[.!?]").exists(_.split("\\s+").length < 5)) {
      Tone.Excited
    }
    // Check for professional language
    else if (mentions(ToneValidator.ProfessionalWords, lower)) {
      Tone.Professional
    }
    // Check for casual language
    else if (mentions(ToneValidator.CasualWords, lower)) {
      Tone.Casual
    }
    // Check for friendly language
    else if (mentions(ToneValidator.FriendlyWords, lower)) {
      Tone.Friendly
    }
    // Check for formal language
    else if (mentions(ToneValidator.FormalWords, lower)) {
      Tone.Formal
    }
    // Default to neutral
    else {
      Tone.Neutral
    }
  }

  // `find`, not `matches(".*kw.*")`: `.` stops at a line break, so a newline anywhere made every keyword check
  // fail and multi-line text always came out Neutral.
  private def mentions(words: Regex, text: String): Boolean =
    words.findFirstIn(text).isDefined

  val name: String = "ToneValidator"

  override val description: Option[String] = Some(
    s"Validates tone is one of: ${allowedTones.map(_.name).mkString(", ")}"
  )
}

object ToneValidator {

  private def wholeWords(words: String*): Regex = s"\\b(${words.mkString("|")})\\b".r

  private val ProfessionalWords: Regex = wholeWords("please", "thank you", "kindly", "regards", "sincerely")
  private val CasualWords: Regex       = wholeWords("hey", "cool", "awesome", "yeah", "nah")
  private val FriendlyWords: Regex     = wholeWords("hi", "hello", "thanks", "appreciate")
  private val FormalWords: Regex       = wholeWords("furthermore", "moreover", "consequently", "therefore")

  /**
   * Create a tone validator that accepts the given tones.
   *
   * @param allowedTones the tones that pass; see [[ToneValidator]] for how a text's tone is detected
   * @return a validator equivalent to `new ToneValidator(allowedTones)`
   */
  def apply(allowedTones: Set[Tone]): ToneValidator =
    new ToneValidator(allowedTones)

  /**
   * A validator that accepts only [[Tone.Professional]] output.
   *
   * Output with no professional keyword, such as plain factual text, is Neutral and is rejected; add
   * [[Tone.Neutral]] through `ToneValidator(Set(Tone.Professional, Tone.Neutral))` to let it through.
   */
  def professionalOnly: ToneValidator =
    new ToneValidator(Set(Tone.Professional))

  /** A validator that accepts [[Tone.Professional]] or [[Tone.Friendly]] output, and rejects the rest. */
  def professionalOrFriendly: ToneValidator =
    new ToneValidator(Set(Tone.Professional, Tone.Friendly))

  /** A validator that accepts [[Tone.Casual]] or [[Tone.Friendly]] output, and rejects the rest. */
  def casualOrFriendly: ToneValidator =
    new ToneValidator(Set(Tone.Casual, Tone.Friendly))

  /** A validator that accepts every tone in `Tone.all`, so `validate` always returns `Right`. */
  def allowAll: ToneValidator =
    new ToneValidator(Tone.all)
}
