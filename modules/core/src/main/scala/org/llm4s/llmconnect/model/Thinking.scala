package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.{ ReadWriter => RW, readwriter }

/**
 * Whether the model thinks before it answers, and how its thinking is bounded: separate from
 * [[ReasoningEffort]], which says how hard to think.
 *
 * Set it with [[CompletionOptions.withThinking]]. A provider maps each case to its own control, and reports what it
 * could not send as asked on `Completion.adjustments` (RFC 0008):
 *
 *  - [[Thinking.Off]]: do not think. Anthropic `thinking: disabled`; on OpenAI, the lowest effort the model accepts.
 *  - [[Thinking.Adaptive]]: the model decides whether and how much to think. Anthropic `thinking: adaptive`.
 *  - [[Thinking.Budget]]: think within a token budget. Anthropic `budget_tokens`, fitted to the provider's range
 *    and reported as `Adjusted` when it does not fit.
 *
 * `@Stable`, not `@Experimental` as RFC 0008 first wrote it: the `@Stable` [[CompletionOptions]] exposes it, as
 * [[RequestOption]] is exposed by `Completion` (RFC 0008 decisions 12 and 14).
 *
 * @example
 * {{{
 * CompletionOptions().withThinking(Thinking.Adaptive).withReasoning(ReasoningEffort.Low)
 * Thinking.budget(8000).map(CompletionOptions().withThinking)   // Result[CompletionOptions]
 * }}}
 */
@Stable
sealed trait Thinking

object Thinking {

  /** Do not think. */
  case object Off extends Thinking

  /** Let the model decide whether and how much to think. */
  case object Adaptive extends Thinking

  /**
   * Think within a budget of `tokens`. Made only by [[Thinking.budget]], so `tokens` is always positive: `Off` and
   * `Adaptive` already say what `0` and `-1` mean to the providers that use them.
   */
  final case class Budget private[Thinking] (tokens: Int) extends Thinking

  /**
   * A thinking budget of `tokens`. Fails with a [[org.llm4s.error.ValidationError]] for `tokens <= 0`, which mean
   * different things to different providers (on Gemini `0` turns thinking off and `-1` makes it dynamic); use
   * [[Off]] or [[Adaptive]] for those.
   */
  def budget(tokens: Int): Result[Thinking] =
    if (tokens > 0) Right(new Budget(tokens))
    else
      Left(
        ValidationError(
          "tokens",
          s"a thinking budget must be positive, got $tokens; use Thinking.Off or Thinking.Adaptive"
        )
      )

  /**
   * Written as `"off"`, `"adaptive"` or `{"budget": n}`. A stored budget is read through [[budget]], so one that is not
   * positive fails to read.
   */
  implicit val rw: RW[Thinking] = readwriter[ujson.Value].bimap[Thinking](
    {
      case Off            => ujson.Str("off")
      case Adaptive       => ujson.Str("adaptive")
      case Budget(tokens) => ujson.Obj("budget" -> tokens)
    },
    {
      case ujson.Str("off")      => Off
      case ujson.Str("adaptive") => Adaptive
      case ujson.Obj(fields) if fields.keySet == Set("budget") && fields("budget").numOpt.exists(_.isValidInt) =>
        budget(fields("budget").num.toInt).fold(e => throw new IllegalArgumentException(e.message), identity)
      case other =>
        throw new IllegalArgumentException(
          s"Expected \"off\", \"adaptive\" or {\"budget\": n} for Thinking, got: $other"
        )
    }
  )
}
