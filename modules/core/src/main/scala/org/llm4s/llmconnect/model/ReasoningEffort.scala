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
 * Whether the model thinks at all, and within what budget, is [[Thinking]]: `ReasoningEffort` says only how hard to
 * think. A level a model does not accept is sent as the nearest one it does, and reported on `Completion.adjustments`.
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
   * Minimal reasoning effort: the least thinking a model that always thinks will do.
   *
   * Provider mapping:
   * - OpenAI: `reasoning_effort: "minimal"`
   * - Anthropic: `output_config.effort: "low"` (Anthropic has no lower level), or a 1024-token budget
   */
  case object Minimal extends ReasoningEffort {
    val name = "minimal"
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
   * Deep thinking for complex tasks.
   *
   * Provider mapping:
   * - OpenAI o1/o3: `reasoning_effort: "high"`
   * - Anthropic: ~32768 budget tokens for thinking
   */
  case object High extends ReasoningEffort {
    val name = "high"
  }

  /**
   * Extra-high reasoning effort, between `High` and `Max`.
   *
   * Provider mapping:
   * - OpenAI: `reasoning_effort: "xhigh"`
   * - Anthropic: `output_config.effort: "xhigh"`, or a 49152-token budget
   */
  case object XHigh extends ReasoningEffort {
    val name = "xhigh"
  }

  /**
   * Maximum reasoning effort.
   *
   * Provider mapping:
   * - OpenAI: `reasoning_effort: "max"`
   * - Anthropic: `output_config.effort: "max"`, or a 65536-token budget
   */
  case object Max extends ReasoningEffort {
    val name = "max"
  }

  /** All available reasoning effort levels, from least to most effort. */
  val values: Seq[ReasoningEffort] = Seq(Minimal, Low, Medium, High, XHigh, Max)

  /**
   * Parse ReasoningEffort from a string (case-insensitive).
   *
   * `"none"` is not a level: it meant "don't think" on some providers and "lowest effort" on others, and
   * [[Thinking.Off]] now says the first. It returns `scala.None` like any other unknown string.
   *
   * @param s the string to parse
   * @return Some(ReasoningEffort) if valid, scala.None otherwise
   */
  def fromString(s: String): Option[ReasoningEffort] = s.toLowerCase.trim match {
    case "minimal" => Some(Minimal)
    case "low"     => Some(Low)
    case "medium"  => Some(Medium)
    case "high"    => Some(High)
    case "xhigh"   => Some(XHigh)
    case "max"     => Some(Max)
    case _         => scala.None
  }

  /**
   * Get the default thinking budget tokens for Anthropic models.
   *
   * @param effort the reasoning effort level
   * @return number of budget tokens
   */
  def defaultBudgetTokens(effort: ReasoningEffort): Int = effort match {
    case Minimal => 1024
    case Low     => 2048
    case Medium  => 8192
    case High    => 32768
    case XHigh   => 49152
    case Max     => 65536
  }

  /**
   * Upickle ReadWriter for serialization/deserialization.
   * Serializes to/from the string name (e.g., "low", "medium", "high"). `"none"` fails to read with a message that
   * names its replacement, [[Thinking.Off]].
   */
  implicit val rw: RW[ReasoningEffort] = readwriter[ujson.Value].bimap[ReasoningEffort](
    effort => ujson.Str(effort.name),
    {
      case ujson.Str(s) if s.trim.equalsIgnoreCase("none") =>
        throw new IllegalArgumentException("\"none\" is no longer a ReasoningEffort; use Thinking.Off")
      case ujson.Str(s) =>
        fromString(s).getOrElse(
          throw new IllegalArgumentException(s"Invalid ReasoningEffort: $s")
        )
      case other =>
        throw new IllegalArgumentException(s"Expected string for ReasoningEffort, got: $other")
    }
  )
}
