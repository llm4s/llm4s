package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

import upickle.default.{ ReadWriter => RW, readwriter }

/**
 * Represents the level of reasoning effort to request from the LLM.
 *
 * Different providers implement reasoning in different ways:
 * - OpenAI o1/o3 models: Uses `reasoning_effort` parameter
 * - Anthropic Claude: Uses extended thinking with `budget_tokens`
 *
 * For non-reasoning models, this setting is silently ignored.
 *
 * @example
 * {{{
 * import org.llm4s.llmconnect.model._
 *
 * // Use high reasoning for complex tasks
 * val options = CompletionOptions().withReasoning(ReasoningEffort.High)
 *
 * // Parse from string
 * val effort = ReasoningEffort.fromString("medium")
 * }}}
 */
@Stable
sealed trait ReasoningEffort {

  /** The string representation used in API calls */
  def name: String
}

object ReasoningEffort {

  /**
   * No extra reasoning - standard completion.
   * Use for simple tasks where extended reasoning adds unnecessary latency.
   */
  case object None extends ReasoningEffort {
    val name = "none"
  }

  /**
   * Low reasoning effort.
   * For tasks requiring slight deliberation but not deep analysis.
   *
   * Provider mapping:
   * - OpenAI o1/o3: `reasoning_effort: "low"`
   * - Anthropic: ~2048 budget tokens for thinking
   */
  case object Low extends ReasoningEffort {
    val name = "low"
  }

  /**
   * Medium reasoning effort.
   * Balanced quality vs latency for moderately complex tasks.
   *
   * Provider mapping:
   * - OpenAI o1/o3: `reasoning_effort: "medium"`
   * - Anthropic: ~8192 budget tokens for thinking
   */
  case object Medium extends ReasoningEffort {
    val name = "medium"
  }

  /**
   * High reasoning effort.
   * Maximum reasoning for complex tasks requiring deep thinking.
   *
   * Provider mapping:
   * - OpenAI o1/o3: `reasoning_effort: "high"`
   * - Anthropic: ~32768 budget tokens for thinking
   */
  case object High extends ReasoningEffort {
    val name = "high"
  }

  /** All available reasoning effort levels */
  val values: Seq[ReasoningEffort] = Seq(None, Low, Medium, High)

  /**
   * Parse ReasoningEffort from a string (case-insensitive).
   *
   * @param s the string to parse
   * @return Some(ReasoningEffort) if valid, scala.None otherwise
   */
  def fromString(s: String): Option[ReasoningEffort] = s.toLowerCase.trim match {
    case "none"   => Some(None)
    case "low"    => Some(Low)
    case "medium" => Some(Medium)
    case "high"   => Some(High)
    case _        => scala.None
  }

  /**
   * Get the default thinking budget tokens for Anthropic models.
   *
   * @param effort the reasoning effort level
   * @return number of budget tokens, 0 for None
   */
  def defaultBudgetTokens(effort: ReasoningEffort): Int = effort match {
    case None   => 0
    case Low    => 2048
    case Medium => 8192
    case High   => 32768
  }

  /**
   * Upickle ReadWriter for serialization/deserialization.
   * Serializes to/from the string name (e.g., "none", "low", "medium", "high").
   */
  implicit val rw: RW[ReasoningEffort] = readwriter[ujson.Value].bimap[ReasoningEffort](
    effort => ujson.Str(effort.name),
    {
      case ujson.Str(s) =>
        fromString(s).getOrElse(
          throw new IllegalArgumentException(s"Invalid ReasoningEffort: $s")
        )
      case other =>
        throw new IllegalArgumentException(s"Expected string for ReasoningEffort, got: $other")
    }
  )
}
