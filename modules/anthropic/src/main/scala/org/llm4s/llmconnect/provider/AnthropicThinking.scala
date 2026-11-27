package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.error.ValidationError
import org.llm4s.model.{ EffortMapping, ModelCapabilities }
import org.llm4s.types.Result

/**
 * Maps `CompletionOptions.thinking` and `reasoning` onto Anthropic's `thinking` and `output_config.effort`
 * (RFC 0008), from the model's registry capabilities. Pure, so the mapping is tested without a client.
 *
 * `thinking`:
 *  - `Off` is `thinking: disabled`. A model that cannot turn thinking off (`supportsThinkingOff = Some(false)`) is
 *    sent no `thinking`, so it thinks as it does by default, reported `Adjusted`.
 *  - `Adaptive` is `thinking: adaptive`. A model the registry knows but not as taking it (4.5 and earlier, which
 *    return a 400) is sent the budget `reasoning` would give, or `Medium`'s, reported `Adjusted`.
 *  - `Budget(n)` is `budget_tokens: n`, fitted to `[1024, max_tokens)` and reported `Adjusted` when fitted. A model
 *    that rejects a budget (`supportsBudgetThinking = Some(false)`) is sent `adaptive`, reported `Adjusted`.
 *
 * A budget the caller did not set in full - one from a `reasoning` level, or an explicit `Budget(n)` under the default
 * `max_tokens` - is capped at half of `max_tokens`, so thinking cannot spend the whole output allowance and leave the
 * answer empty (#3125), reported `Adjusted`. A budget of 1024 that is not below `max_tokens` is no valid request;
 * [[belowMaxTokens]] refuses it before anything is sent.
 *
 * `reasoning`, with no `thinking`, keeps today's request: a `budget_tokens` budget from the level. The exception is a
 * model that rejects a budget, which is sent `adaptive` with the level as `output_config.effort`, reported `Adjusted`
 * (decision 11). A new level (`Minimal`, `XHigh`, `Max`) on a model that takes `output_config` is sent as the effort,
 * thinking adaptively where the model can and within the level's budget where it cannot.
 *
 * With `thinking` set, `reasoning` is sent as `output_config.effort` where the model takes it, at the nearest level
 * the model accepts (Anthropic has no `minimal`), reported `Adjusted` when it differs. On a model without
 * `output_config` there is nowhere to send it: with `Off` it is overridden (reported `Adjusted`), otherwise `Dropped`.
 *
 * A model the registry does not know (`caps = None`) is sent what was asked, since the registry cannot say otherwise.
 */
private[provider] object AnthropicThinking {

  /** What goes in the request's `thinking`. */
  enum Mode {
    case Disabled, Adaptive
    case Enabled(budgetTokens: Int)
  }

  /** The request's `thinking` and `output_config.effort`, and what was not sent as asked. */
  final case class Plan(thinking: Option[Mode], effort: Option[String], adjustments: List[RequestAdjustment]) {

    /** Whether the request asks the model to think, so that it must not set a temperature. */
    def thinks: Boolean = thinking.exists(_ != Mode.Disabled)
  }

  /** Anthropic's effort levels: `output_config.effort` has no `minimal`. */
  private val AnthropicLevels: Set[ReasoningEffort] = ReasoningEffort.values.toSet - ReasoningEffort.Minimal

  private val NewLevels: Set[ReasoningEffort] = Set(ReasoningEffort.Minimal, ReasoningEffort.XHigh, ReasoningEffort.Max)

  /** The smallest `budget_tokens` Anthropic takes. */
  val MinBudget: Int = 1024

  /**
   * `plan`, or a `ValidationError` when it thinks within a budget that is not below `maxTokens`: with `maxTokens` of
   * 1024 or less no budget is, and Anthropic answers 400.
   */
  def belowMaxTokens(plan: Plan, maxTokens: Int): Result[Plan] =
    plan.thinking match {
      case Some(Mode.Enabled(budget)) if budget >= maxTokens =>
        Left(
          ValidationError(
            "maxTokens",
            s"thinking needs max_tokens above its budget ($budget, at least $MinBudget); max_tokens is $maxTokens"
          )
        )
      case _ => Right(plan)
    }

  def plan(model: String, options: CompletionOptions, maxTokens: Int, caps: Option[ModelCapabilities]): Plan = {
    def flag(f: ModelCapabilities => Option[Boolean]): Option[Boolean] = caps.flatMap(f)
    val rejectsBudget                                                  = flag(_.supportsBudgetThinking).contains(false)
    val takesEffort                                                    = flag(_.supportsOutputConfig).contains(true)
    // a known model without the flag is one from before adaptive thinking; an unknown model is sent what was asked
    val takesAdaptive = caps.isEmpty || flag(_.supportsAdaptiveThinking).contains(true)
    val accepted =
      caps.flatMap(_.supportedReasoningEfforts).map(_ & AnthropicLevels).filter(_.nonEmpty).getOrElse(AnthropicLevels)

    def effortFor(level: ReasoningEffort): (String, List[RequestAdjustment]) = {
      val sent = EffortMapping.nearest(level, accepted).name
      (sent, EffortMapping.level(model, level, sent))
    }

    // the room thinking may take: all but one token only when the caller set both the budget and max_tokens
    def ceiling(explicitBudget: Boolean): Int =
      if (explicitBudget && options.maxTokens.isDefined) maxTokens - 1 else maxTokens / 2

    def fit(tokens: Int, explicitBudget: Boolean = false): Int =
      math.max(MinBudget, math.min(tokens, ceiling(explicitBudget)))

    def fitReason(explicitBudget: Boolean): String =
      if (explicitBudget && options.maxTokens.isDefined)
        s"Anthropic takes a budget of at least $MinBudget and below max_tokens ($maxTokens)"
      else
        s"Anthropic takes a budget of at least $MinBudget; capped at half of max_tokens ($maxTokens) to leave room for the answer"

    def fitted(tokens: Int): (Mode, List[RequestAdjustment]) = {
      val sent = fit(tokens, explicitBudget = true)
      val report =
        if (sent == tokens) Nil
        else
          List(
            RequestAdjustment.Adjusted(
              RequestOption.Thinking,
              s"Budget($tokens)",
              s"Budget($sent)",
              fitReason(explicitBudget = true)
            )
          )
      (Mode.Enabled(sent), report)
    }

    /** A level's default budget, fitted, and the report when the fitted budget is not the level's. */
    def levelBudget(level: ReasoningEffort): (Mode, List[RequestAdjustment]) = {
      val tokens = ReasoningEffort.defaultBudgetTokens(level)
      val sent   = fit(tokens)
      val report =
        if (sent == tokens) Nil
        else
          List(
            RequestAdjustment.Adjusted(RequestOption.Reasoning, s"Budget($tokens)", s"Budget($sent)", fitReason(false))
          )
      (Mode.Enabled(sent), report)
    }

    /** `reasoning` alongside an explicit `thinking`: the effort where the model takes one. */
    def effortWithThinking(overriddenAs: Option[String]): (Option[String], List[RequestAdjustment]) =
      options.reasoning match {
        case None => (None, Nil)
        case Some(level) if takesEffort =>
          val (sent, report) = effortFor(level)
          (Some(sent), report)
        case Some(_) if overriddenAs.isDefined =>
          (None, EffortMapping.overriddenByOff(options, overriddenAs.get))
        case Some(_) =>
          val reason = s"$model takes no effort level alongside a thinking setting"
          (None, List(RequestAdjustment.Dropped(RequestOption.Reasoning, reason)))
      }

    options.thinking match {
      case Some(Thinking.Off) =>
        val (mode, offReport) =
          if (flag(_.supportsThinkingOff).contains(false))
            (
              None,
              List(
                RequestAdjustment.Adjusted(
                  RequestOption.Thinking,
                  "Off",
                  "the model's default",
                  s"$model cannot turn thinking off"
                )
              )
            )
          else (Some(Mode.Disabled), Nil)
        val (effort, reasoningReport) = effortWithThinking(Some("thinking disabled"))
        Plan(mode, effort, offReport ++ reasoningReport)

      case Some(Thinking.Adaptive) =>
        val (mode, adaptiveReport) =
          if (takesAdaptive) (Mode.Adaptive, Nil)
          else {
            val sent   = fit(ReasoningEffort.defaultBudgetTokens(options.reasoning.getOrElse(ReasoningEffort.Medium)))
            val reason = s"$model does not take adaptive thinking"
            (
              Mode.Enabled(sent),
              List(RequestAdjustment.Adjusted(RequestOption.Thinking, "Adaptive", s"Budget($sent)", reason))
            )
          }
        val (effort, reasoningReport) = effortWithThinking(None)
        Plan(Some(mode), effort, adaptiveReport ++ reasoningReport)

      case Some(Thinking.Budget(tokens)) =>
        val (mode, budgetReport) =
          if (rejectsBudget)
            (
              Mode.Adaptive,
              List(
                RequestAdjustment.Adjusted(
                  RequestOption.Thinking,
                  s"Budget($tokens)",
                  "Adaptive",
                  "model does not accept a thinking budget"
                )
              )
            )
          else fitted(tokens)
        val (effort, reasoningReport) = effortWithThinking(None)
        Plan(Some(mode), effort, budgetReport ++ reasoningReport)

      case None =>
        options.reasoning match {
          case None => Plan(None, None, Nil)
          case Some(level) if rejectsBudget =>
            val (sent, levelReport) = effortFor(level)
            val exception = RequestAdjustment.Adjusted(
              RequestOption.Reasoning,
              "budget_tokens",
              "output_config.effort",
              "model does not accept a thinking budget"
            )
            Plan(Some(Mode.Adaptive), Some(sent), exception :: levelReport)
          case Some(level) if NewLevels(level) && takesEffort =>
            val (sent, levelReport) = effortFor(level)
            val (mode, capReport) =
              if (flag(_.supportsAdaptiveThinking).contains(true)) (Mode.Adaptive, Nil)
              else levelBudget(level)
            Plan(Some(mode), Some(sent), levelReport ++ capReport)
          case Some(level) =>
            // the level's default budget, leaving half of max_tokens for the answer
            val (mode, capReport) = levelBudget(level)
            Plan(Some(mode), None, capReport)
        }
    }
  }
}
