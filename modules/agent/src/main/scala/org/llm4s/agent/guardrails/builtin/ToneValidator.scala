package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Tone categories for content validation.
 *
 * Represents the detected or expected tone of text content.
 * Used by [[ToneValidator]] to enforce tone requirements on LLM output.
 */
sealed trait Tone {
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
 * Validates that output matches one of the allowed tones.
 *
 * This is a simple keyword-based implementation.
 * For production, consider using sentiment analysis APIs or ML models.
 *
 * @param allowedTones The set of acceptable tones
 */
class ToneValidator(allowedTones: Set[Tone]) extends OutputGuardrail {

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
    else if (lower.matches(".*\\b(please|thank you|kindly|regards|sincerely)\\b.*")) {
      Tone.Professional
    }
    // Check for casual language
    else if (lower.matches(".*\\b(hey|cool|awesome|yeah|nah)\\b.*")) {
      Tone.Casual
    }
    // Check for friendly language
    else if (lower.matches(".*\\b(hi|hello|thanks|appreciate)\\b.*")) {
      Tone.Friendly
    }
    // Check for formal language
    else if (lower.matches(".*\\b(furthermore|moreover|consequently|therefore)\\b.*")) {
      Tone.Formal
    }
    // Default to neutral
    else {
      Tone.Neutral
    }
  }

  val name: String = "ToneValidator"

  override val description: Option[String] = Some(
    s"Validates tone is one of: ${allowedTones.map(_.name).mkString(", ")}"
  )
}

object ToneValidator {

  /**
   * Create a tone validator with specified allowed tones.
   */
  def apply(allowedTones: Set[Tone]): ToneValidator =
    new ToneValidator(allowedTones)

  /**
   * Create a tone validator allowing only professional tone.
   */
  def professionalOnly: ToneValidator =
    new ToneValidator(Set(Tone.Professional))

  /**
   * Create a tone validator allowing professional or friendly tones.
   */
  def professionalOrFriendly: ToneValidator =
    new ToneValidator(Set(Tone.Professional, Tone.Friendly))

  /**
   * Create a tone validator allowing casual or friendly tones.
   */
  def casualOrFriendly: ToneValidator =
    new ToneValidator(Set(Tone.Casual, Tone.Friendly))

  /**
   * Create a tone validator allowing all tones (effectively no validation).
   */
  def allowAll: ToneValidator =
    new ToneValidator(Tone.all)
}
