package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.model.{ EffortMapping, ModelCapabilities }

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

    def fit(tokens: Int): Int = math.max(1024, math.min(tokens, maxTokens - 1))

    def fitted(tokens: Int): (Mode, List[RequestAdjustment]) = {
      val sent = fit(tokens)
      val report =
        if (sent == tokens) Nil
        else
          List(
            RequestAdjustment.Adjusted(
              RequestOption.Thinking,
              s"Budget($tokens)",
              s"Budget($sent)",
              s"Anthropic takes a budget of at least 1024 and below max_tokens ($maxTokens)"
            )
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
            val mode =
              if (flag(_.supportsAdaptiveThinking).contains(true)) Mode.Adaptive
              else Mode.Enabled(fit(ReasoningEffort.defaultBudgetTokens(level)))
            Plan(Some(mode), Some(sent), levelReport)
          case Some(level) =>
            // as before RFC 0008: the level's default budget, fitted as it always was
            Plan(Some(Mode.Enabled(fit(ReasoningEffort.defaultBudgetTokens(level)))), None, Nil)
        }
    }
  }
}
