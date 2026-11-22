package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.model.EffortMapping

/**
 * `CompletionOptions.thinking` and `reasoning` as Ollama's `think` field (RFC 0008, Part 2).
 *
 * Ollama's `/api/chat` takes `think` as `true` (think), `false` (don't), or a level the model defines, such as `"low"`,
 * `"medium"` or `"high"`; `/api/show` lists the values a model takes in `thinking.values`
 * (https://docs.ollama.com/api/chat, https://docs.ollama.com/capabilities/thinking). "Models that cannot disable
 * thinking use their lowest supported level instead" of `false`, and a level the model lacks "use[s] the model default".
 *
 * The mapping, one control serving both options:
 *
 *  - `thinking = Off`: `false`, or the lowest level for a model that takes only levels (`Adjusted`); a `reasoning` it
 *    overrides is reported `Adjusted`.
 *  - `reasoning` (and `thinking` not `Off`): the nearest level the model takes (`Adjusted` when not the one asked for);
 *    on a model that takes only on and off, `true` (`Adjusted`).
 *  - `thinking = Adaptive` alone: `true`; a model that takes only levels sets how much it thinks by its default level
 *    (`NotNeeded`).
 *  - `thinking = Budget(n)`: Ollama takes no budget. `true` on a model that takes on and off (`Adjusted`), else not sent
 *    (`Dropped`).
 *  - A model whose only value is `false`, or, from a server without that metadata, whose `capabilities` lack
 *    `thinking`, does not think: a request to think is `Dropped`, `Off` is `NotNeeded`, and no `think` is sent.
 */
private[provider] object OllamaThink {

  /**
   * The `think` values a model takes.
   *
   * @param levels the named levels that are [[ReasoningEffort]]s, lowest first; a model-defined name that is not one
   *               is never sent
   * @param on whether it takes `true`
   * @param off whether it takes `false`
   */
  final case class Controls(levels: List[ReasoningEffort], on: Boolean, off: Boolean) {
    def thinks: Boolean = on || levels.nonEmpty
  }

  /** What one request sends as `think`, if anything, and what is reported. */
  final case class Plan(think: Option[ujson.Value], adjustments: List[RequestAdjustment])

  private val OnOff = Controls(Nil, on = true, off = true)

  // gpt-oss takes only levels: "low", "medium" and "high", the levels /api/show lists for it
  private val GptOssLevels =
    Controls(List(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High), false, false)

  /**
   * The controls of a model whose `/api/show` reply lists none (a server older than the `thinking` metadata): levels for
   * OpenAI's gpt-oss models, the one family that takes them, and on and off for every other.
   */
  def byName(model: String): Controls =
    if (model.toLowerCase(java.util.Locale.ROOT).contains("gpt-oss")) GptOssLevels else OnOff

  // a model that does not think: `think: true` is refused for it (Ollama 0.11's chat handler checks the capability)
  private val NoThinking = Controls(Nil, on = false, off = false)

  /**
   * The controls an `/api/show` reply gives `model`: the values `thinking.values` lists; else, from a server older than
   * that metadata, [[byName]]'s for a model whose `capabilities` include `thinking` and none for one whose do not, so a
   * model that cannot think is never sent `think: true`; `None` when the reply says neither.
   */
  def fromShow(model: String, reply: ujson.Value): Option[Controls] = {
    val fields = reply.objOpt
    val listed = fields
      .flatMap(_.get("thinking"))
      .flatMap(_.objOpt)
      .flatMap(_.get("values"))
      .flatMap(_.arrOpt)
      .map { values =>
        val levels = values.flatMap(_.strOpt).flatMap(ReasoningEffort.fromString).toSet
        Controls(
          ReasoningEffort.values.filter(levels.contains).toList,
          on = values.contains(ujson.True),
          off = values.contains(ujson.False)
        )
      }
    listed.orElse(
      fields
        .flatMap(_.get("capabilities"))
        .flatMap(_.arrOpt)
        .map(capabilities => if (capabilities.contains(ujson.Str("thinking"))) byName(model) else NoThinking)
    )
  }

  /** Whether `options` asks for anything `think` carries, so the model's controls are needed. */
  def asked(options: CompletionOptions): Boolean = options.thinking.isDefined || options.reasoning.isDefined

  /** The `think` value and the report for `options` on `model`, given the values it takes. */
  def plan(model: String, options: CompletionOptions, controls: Controls): Plan =
    if (!asked(options)) Plan(None, Nil)
    else if (!controls.thinks) notThinking(model, options)
    else
      options.thinking match {
        case Some(Thinking.Off) => off(model, options, controls)
        case thinking =>
          options.reasoning match {
            case Some(level) if controls.levels.nonEmpty =>
              val sent = EffortMapping.nearest(level, controls.levels.toSet).name
              Plan(Some(ujson.Str(sent)), EffortMapping.level(model, level, sent) ++ budgetDropped(model, thinking))
            case reasoning =>
              val asOnOff = reasoning.toList.map(level =>
                RequestAdjustment.Adjusted(
                  RequestOption.Reasoning,
                  level.name,
                  "true",
                  s"$model takes thinking on or off, not a level"
                )
              )
              thinking match {
                case Some(Thinking.Budget(tokens)) if controls.on =>
                  val adjusted = RequestAdjustment.Adjusted(
                    RequestOption.Thinking,
                    s"Budget($tokens)",
                    "true",
                    "Ollama's think takes no token budget"
                  )
                  Plan(Some(ujson.True), adjusted :: asOnOff)
                case Some(_: Thinking.Budget) => Plan(None, budgetDropped(model, thinking))
                case Some(_) if !controls.on =>
                  Plan(
                    None,
                    List(
                      RequestAdjustment.NotNeeded(
                        RequestOption.Thinking,
                        s"$model sets how much it thinks by its level"
                      )
                    )
                  )
                case _ => Plan(Some(ujson.True), asOnOff)
              }
          }
      }

  private def off(model: String, options: CompletionOptions, controls: Controls): Plan =
    if (controls.off || controls.levels.isEmpty)
      Plan(Some(ujson.False), EffortMapping.overriddenByOff(options, "false"))
    else {
      val lowest = controls.levels.head.name
      Plan(
        Some(ujson.Str(lowest)),
        RequestAdjustment.Adjusted(RequestOption.Thinking, "Off", lowest, s"$model cannot turn thinking off") ::
          EffortMapping.overriddenByOff(options, lowest)
      )
    }

  private def budgetDropped(model: String, thinking: Option[Thinking]): List[RequestAdjustment] =
    thinking.collect { case _: Thinking.Budget =>
      RequestAdjustment.Dropped(RequestOption.Thinking, s"Ollama takes no thinking budget for $model")
    }.toList

  private def notThinking(model: String, options: CompletionOptions): Plan = {
    val reason = s"$model does not think"
    val thinking = options.thinking.toList.map {
      case Thinking.Off => RequestAdjustment.NotNeeded(RequestOption.Thinking, reason)
      case _            => RequestAdjustment.Dropped(RequestOption.Thinking, reason)
    }
    Plan(
      None,
      thinking ++ options.reasoning.toList.map(_ => RequestAdjustment.Dropped(RequestOption.Reasoning, reason))
    )
  }
}
