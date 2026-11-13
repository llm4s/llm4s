package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.model.{ EffortMapping, ModelCapabilities, UnmappedOptions }

/**
 * Maps `CompletionOptions.thinking` and `reasoning` onto Gemini's `generationConfig.thinkingConfig`, for the Gemini
 * API and Vertex AI alike (RFC 0008's Gemini follow-up). Pure, so the mapping is tested without a client.
 *
 * Gemini has two controls, by model generation, and a request must not carry both:
 *
 *  - '''Gemini 2.5 takes `thinkingBudget`''': `0` turns thinking off (Flash and Flash-Lite only), `-1` makes it
 *    dynamic, and a positive budget must be in the model's range: 2.5 Pro 128-32,768, 2.5 Flash 1-24,576, 2.5
 *    Flash-Lite 512-24,576. `Thinking.Off` is `0` (2.5 Pro, which cannot turn thinking off, gets its minimum, reported
 *    `Adjusted`), `Adaptive` is `-1`, and `Budget(n)` is `n` fitted to the range (`Adjusted` when fitted). A 2.5 model
 *    takes no level, so a `reasoning` is `Dropped`.
 *  - '''Gemini 3 and later take `thinkingLevel`''': `MINIMAL` (the Flash models), `LOW`, `MEDIUM`, `HIGH`, and `XHIGH`
 *    (Gemini 4 Hydrogen). `reasoning` is its level, or the nearest the model has (`Adjusted`). No way to turn thinking
 *    off is documented, so `Thinking.Off` is the lowest level (`Adjusted`) and wins over a `reasoning`. `Adaptive` is
 *    `NotNeeded` (the model decides by default) and `Budget(n)` is `Dropped`: `thinkingBudget` is deprecated on these
 *    models, and a request with both is an error.
 *
 * Which generation a model is comes from its name, as the RFC has a provider's own naming rules do. A model the
 * registry knows not to reason, an image, audio, live or speech model, or any other model is sent nothing and reports
 * both as `Dropped`, as before.
 */
private[provider] object GeminiThinking {

  /** The request's `thinkingConfig`, if any, and what was not sent as asked. */
  final case class Plan(thinkingConfig: Option[ujson.Obj], adjustments: List[RequestAdjustment])

  /** How a Gemini model takes thinking. */
  enum Family {
    case Budget(min: Int, max: Int, canTurnOff: Boolean)
    case Level(levels: Set[ReasoningEffort])
    case Unmapped
  }

  private val Generation  = """gemini-(\d+)(?:\.\d+)?(?:-.*)?""".r
  private val NotThinking = Set("image", "tts", "live", "audio", "embedding")

  /** The family of a model id, matched case-insensitively, a `models/` or router prefix ignored. */
  def family(model: String, caps: Option[ModelCapabilities]): Family = {
    val name = model.trim.toLowerCase.split('/').last
    if (caps.flatMap(_.supportsReasoning).contains(false) || NotThinking.exists(name.contains)) Family.Unmapped
    else if (name.startsWith("gemini-2.5-pro")) Family.Budget(128, 32768, canTurnOff = false)
    else if (name.startsWith("gemini-2.5-flash-lite")) Family.Budget(512, 24576, canTurnOff = true)
    else if (name.startsWith("gemini-2.5-flash")) Family.Budget(1, 24576, canTurnOff = true)
    else
      name match {
        case Generation(major) if major.toInt >= 3 =>
          val base        = Set[ReasoningEffort](ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High)
          val withMinimal = if (name.contains("flash")) base + ReasoningEffort.Minimal else base
          Family.Level(if (name.contains("hydrogen")) withMinimal + ReasoningEffort.XHigh else withMinimal)
        case _ => Family.Unmapped
      }
  }

  def plan(model: String, options: CompletionOptions, caps: Option[ModelCapabilities]): Plan =
    family(model, caps) match {
      case Family.Unmapped                     => Plan(None, UnmappedOptions.reasoningAndThinking(model, options))
      case Family.Budget(min, max, canTurnOff) => budgetPlan(model, options, min, max, canTurnOff)
      case Family.Level(levels)                => levelPlan(model, options, levels)
    }

  private def budget(tokens: Int): Option[ujson.Obj] = Some(ujson.Obj("thinkingBudget" -> tokens))

  private def budgetPlan(model: String, options: CompletionOptions, min: Int, max: Int, canTurnOff: Boolean): Plan = {
    val reasoningDropped = options.reasoning.toList.map(_ =>
      RequestAdjustment.Dropped(RequestOption.Reasoning, s"$model takes a thinking budget, not a reasoning level")
    )
    options.thinking match {
      case None                             => Plan(None, reasoningDropped)
      case Some(Thinking.Off) if canTurnOff => Plan(budget(0), reasoningDropped)
      case Some(Thinking.Off) =>
        val report = RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Off",
          s"Budget($min)",
          s"$model cannot turn thinking off; its minimum budget is sent"
        )
        Plan(budget(min), report :: reasoningDropped)
      case Some(Thinking.Adaptive) => Plan(budget(-1), reasoningDropped)
      case Some(Thinking.Budget(tokens)) =>
        val sent = math.max(min, math.min(tokens, max))
        val fitted =
          if (sent == tokens) Nil
          else
            List(
              RequestAdjustment.Adjusted(
                RequestOption.Thinking,
                s"Budget($tokens)",
                s"Budget($sent)",
                s"$model takes a thinking budget from $min to $max"
              )
            )
        Plan(budget(sent), fitted ++ reasoningDropped)
    }
  }

  private def level(effort: ReasoningEffort): Option[ujson.Obj] =
    Some(ujson.Obj("thinkingLevel" -> effort.name.toUpperCase))

  private def levelPlan(model: String, options: CompletionOptions, levels: Set[ReasoningEffort]): Plan = {
    def nearest(effort: ReasoningEffort): (ReasoningEffort, List[RequestAdjustment]) = {
      val sent = EffortMapping.nearest(effort, levels)
      (sent, EffortMapping.level(model, effort, sent.name))
    }
    options.thinking match {
      case Some(Thinking.Off) =>
        val lowest = ReasoningEffort.values.find(levels.contains).getOrElse(ReasoningEffort.Low)
        val report = RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Off",
          lowest.name,
          s"$model cannot turn thinking off; its lowest thinking level is sent"
        )
        Plan(level(lowest), report :: EffortMapping.overriddenByOff(options, lowest.name))
      case thinking =>
        val thinkingReport = thinking.toList.collect {
          case Thinking.Adaptive =>
            RequestAdjustment.NotNeeded(RequestOption.Thinking, s"$model decides how much to think")
          case Thinking.Budget(_) =>
            RequestAdjustment.Dropped(
              RequestOption.Thinking,
              s"$model takes a thinking level, not a budget (thinkingBudget is deprecated for Gemini 3 and later)"
            )
        }
        options.reasoning match {
          case None => Plan(None, thinkingReport)
          case Some(effort) =>
            val (sent, levelReport) = nearest(effort)
            Plan(level(sent), thinkingReport ++ levelReport)
        }
    }
  }
}
