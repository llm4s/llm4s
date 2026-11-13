package org.llm4s.llmconnect.provider

import com.openai.models.{ ReasoningEffort => SdkReasoningEffort }
import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.model.{ EffortMapping, ModelCapabilities, ModelRegistryService }

/**
 * Decides whether [[OpenAIClient]] treats a model as an OpenAI reasoning model, and how it
 * maps [[org.llm4s.llmconnect.model.ReasoningEffort ReasoningEffort]] onto the chat-completions `reasoning_effort` parameter.
 *
 * The model registry is the source of truth, as it is for the rest of llm4s's model-specific
 * request shaping (`RequestTransformer`): a model the registry knows as an OpenAI or Azure
 * model is a reasoning model exactly when its `supports_reasoning` flag is set (the o-series
 * and the gpt-5 family; not gpt-4o, gpt-4.1 or gpt-3.5). Only a model the registry cannot
 * resolve falls back to OpenAI's naming (see [[namedLikeReasoningModel]]), so that a model
 * newer than the bundled metadata snapshot is still recognised.
 */
private[provider] object OpenAIReasoning {

  /** What is known about a model's reasoning support. */
  enum Support {

    /** An OpenAI reasoning model: it takes `reasoning_effort` and `max_completion_tokens`. */
    case Reasoning

    /** An OpenAI model the registry knows does not reason; OpenAI rejects `reasoning_effort` for it. */
    case NonReasoning

    /**
     * Not resolvable to an OpenAI model: a model the registry does not know, a model from
     * another provider (Requesty routes to many), or an Azure deployment named freely.
     */
    case Unknown
  }

  /** The registry's `litellm_provider` values for models served by OpenAI's API or Azure OpenAI. */
  private val openAIProviders = Set("openai", "azure", "text-completion-openai")

  /**
   * Whether `model` is an OpenAI reasoning model, according to `registry` first and to its
   * name only when the registry cannot resolve it to an OpenAI model.
   *
   * A fine-tuned model is judged by the model it was trained from (see [[baseModel]]): a
   * fine-tune of `o4-mini` takes `reasoning_effort` and rejects sampling parameters exactly as
   * `o4-mini` does. The registry is asked for the base as named - usually a dated snapshot such
   * as `o4-mini-2025-04-16` - then for it without its date, before the name fallback.
   */
  def support(model: String, registry: ModelRegistryService): Support = {
    val base = baseModel(model)
    // Only a fine-tune's base is also tried undated: stripping a date-like tail from any other
    // id (an Azure deployment called `chat-0613`) could let the registry's fuzzy lookup match
    // an unrelated model.
    val candidates = if (base == model) Seq(model) else Seq(model, base, undated(base)).distinct
    candidates.iterator
      .map(registrySupport(_, registry))
      .collectFirst { case Some(known) => known }
      .getOrElse(if (namedLikeReasoningModel(base)) Support.Reasoning else Support.Unknown)
  }

  /** The registry's answer for `model`, if it resolves `model` to an OpenAI or Azure entry. */
  private def registrySupport(model: String, registry: ModelRegistryService): Option[Support] =
    registry.lookup(model).toOption.filter(meta => openAIProviders.contains(meta.provider.toLowerCase)).map { meta =>
      if (meta.capabilities.supportsReasoning.contains(true)) Support.Reasoning else Support.NonReasoning
    }

  /**
   * The model a fine-tuned model id was trained from, or `model` itself if it is not a
   * fine-tune id in a form OpenAI or Azure documents:
   *  - `ft:<base>:<org>:<suffix>:<id>`, the current form. `<suffix>` may be empty
   *    (`ft:gpt-4o-mini-2024-07-18:acme::9AbC`), and a checkpoint appends `:ckpt-step-<n>`.
   *  - `<base>:ft-<org>:<suffix>-<date>`, the legacy form of the GPT-3 fine-tunes
   *    (`curie:ft-acme-2023-01-01-00-00-00`).
   *  - `<base>.ft-<id>`, the model name of an Azure OpenAI fine-tune
   *    (`gpt-4o-mini-2024-07-18.ft-0e20...`). An Azure *deployment* of one is named freely, and
   *    like any unrecognised deployment is sent `reasoning_effort` only when it is asked for.
   *
   * A router prefix (`openai/ft:...`) goes with the fine-tune wrapping. An id that starts like a
   * fine-tune but names no base (`ft:`, `ft::x`) is returned unchanged, and so resolves to
   * nothing.
   */
  def baseModel(model: String): String = {
    val name = model.trim.split('/').last
    val base =
      if (name.startsWith("ft:")) name.split(':').lift(1).getOrElse("")
      else if (name.contains(":ft-")) name.takeWhile(_ != ':')
      else if (name.contains(".ft-")) name.substring(0, name.indexOf(".ft-"))
      else name
    if (base.isEmpty || base == name) model else base
  }

  /** A model id without a trailing snapshot date (`-2025-04-16`) or version (`-0613`). */
  private def undated(model: String): String = model.replaceFirst("-(\\d{4}-\\d{2}-\\d{2}|\\d{4})$", "")

  /**
   * OpenAI's reasoning-model naming, for models the registry does not know: an `o<digit>`
   * model (`o1`, `o3-mini`, `o4-mini`), a `gpt-<n>` model from gpt-5 on (`gpt-5.6-terra`,
   * `gpt-6-astra`), or an open-weight `gpt-oss` model. A router prefix such as `openai/` is
   * ignored, and a fine-tune is named by its base. The generation is one digit, so Azure's
   * `gpt-35-turbo` is not mistaken for one.
   */
  def namedLikeReasoningModel(model: String): Boolean = {
    val name = baseName(baseModel(model))
    name.matches("o\\d.*") || name.startsWith("gpt-oss") || name.matches("gpt-[5-9](\\D.*)?")
  }

  /**
   * Whether the model rejects sampling parameters (`temperature`, `top_p`, the penalties).
   * OpenAI's own reasoning models do; the open-weight gpt-oss models, served by other hosts,
   * accept them.
   */
  def restrictsSampling(model: String): Boolean = !baseName(baseModel(model)).startsWith("gpt-oss")

  /** The `reasoning_effort` value for an llm4s effort level: OpenAI has one for each. */
  def toSdk(effort: ReasoningEffort): SdkReasoningEffort = effort match {
    case ReasoningEffort.Minimal => SdkReasoningEffort.MINIMAL
    case ReasoningEffort.Low     => SdkReasoningEffort.LOW
    case ReasoningEffort.Medium  => SdkReasoningEffort.MEDIUM
    case ReasoningEffort.High    => SdkReasoningEffort.HIGH
    case ReasoningEffort.XHigh   => SdkReasoningEffort.XHIGH
    case ReasoningEffort.Max     => SdkReasoningEffort.MAX
  }

  /**
   * The registry's capabilities for `model`, tried as [[support]] tries it: as named, then a fine-tune's base, dated
   * and undated. `None` when the registry does not resolve it to an OpenAI or Azure model.
   */
  def capabilities(model: String, registry: ModelRegistryService): Option[ModelCapabilities] = {
    val base       = baseModel(model)
    val candidates = if (base == model) Seq(model) else Seq(model, base, undated(base)).distinct
    candidates.iterator
      .flatMap(m => registry.lookup(m).toOption.filter(meta => openAIProviders.contains(meta.provider.toLowerCase)))
      .map(_.capabilities)
      .nextOption()
  }

  /** What to send as `reasoning_effort`, and what of `reasoning` and `thinking` was not sent as asked. */
  final case class EffortPlan(effort: Option[SdkReasoningEffort], adjustments: List[RequestAdjustment])

  /**
   * Maps `reasoning` and `thinking` onto OpenAI's one control, `reasoning_effort` (RFC 0008):
   *
   *  - `reasoning` is sent as its level. A level the model does not accept, by its registry capabilities, is sent as
   *    the nearest one it does (the lower on a tie) and reported `Adjusted`; with no capabilities it is sent as asked.
   *  - `Thinking.Off` asks for the lowest effort the model accepts: `none` where it can turn thinking off, else its
   *    lowest level, reported `Adjusted`. Where the lowest level is unknown nothing is sent, so the model's default
   *    applies, reported `Dropped`. Off wins over a `reasoning` also set, which is reported `Adjusted`.
   *  - `Thinking.Budget` is `Dropped`: OpenAI takes no thinking budget. `Thinking.Adaptive` is `NotNeeded`: an
   *    OpenAI reasoning model already decides how much to think.
   *
   * `sendsEffort` is false for a model that must not be sent `reasoning_effort` (one known not to reason, or an
   * unknown non-Azure model): nothing is sent, and a `thinking` is reported as not sent.
   */
  def plan(
    model: String,
    options: CompletionOptions,
    caps: Option[ModelCapabilities],
    sendsEffort: Boolean
  ): EffortPlan =
    if (!sendsEffort) {
      val reason = s"$model is not known to be an OpenAI reasoning model"
      EffortPlan(
        None,
        options.thinking.toList.map {
          case Thinking.Off => RequestAdjustment.NotNeeded(RequestOption.Thinking, reason)
          case _            => RequestAdjustment.Dropped(RequestOption.Thinking, reason)
        }
      )
    } else
      options.thinking match {
        case Some(Thinking.Off) => thinkingOff(model, options.reasoning, caps)
        case thinking =>
          val thinkingReport = thinking.toList.collect {
            case Thinking.Adaptive =>
              RequestAdjustment.NotNeeded(RequestOption.Thinking, s"$model decides how much to think")
            case Thinking.Budget(_) =>
              RequestAdjustment.Dropped(RequestOption.Thinking, "OpenAI takes no thinking budget")
          }
          val supported = caps.flatMap(_.supportedReasoningEfforts).filter(_.nonEmpty)
          options.reasoning match {
            case None => EffortPlan(None, thinkingReport)
            case Some(level) =>
              val sent = supported.fold(level)(EffortMapping.nearest(level, _))
              val reasoningReport =
                if (sent == level) Nil
                else
                  List(
                    RequestAdjustment.Adjusted(
                      RequestOption.Reasoning,
                      level.name,
                      sent.name,
                      s"$model does not accept reasoning_effort ${level.name}"
                    )
                  )
              EffortPlan(Some(toSdk(sent)), thinkingReport ++ reasoningReport)
          }
      }

  private def thinkingOff(
    model: String,
    reasoning: Option[ReasoningEffort],
    caps: Option[ModelCapabilities]
  ): EffortPlan = {
    val lowest =
      caps.flatMap(_.supportedReasoningEfforts).flatMap(levels => ReasoningEffort.values.find(levels.contains))
    val (effort, offReport) =
      if (caps.flatMap(_.supportsThinkingOff).contains(true)) (Some(SdkReasoningEffort.NONE), Nil)
      else
        lowest match {
          case Some(level) =>
            val reason = s"$model cannot turn thinking off; its lowest effort is sent"
            (Some(toSdk(level)), List(RequestAdjustment.Adjusted(RequestOption.Thinking, "Off", level.name, reason)))
          case None =>
            val reason = s"$model's lowest effort is not known, so the model's default effort applies"
            (None, List(RequestAdjustment.Dropped(RequestOption.Thinking, reason)))
        }
    val overridden = reasoning.toList.map { level =>
      val sent = effort.fold("nothing")(_.asString)
      RequestAdjustment.Adjusted(RequestOption.Reasoning, level.name, sent, "thinking = Off takes precedence")
    }
    EffortPlan(effort, offReport ++ overridden)
  }

  private def baseName(model: String): String = model.trim.toLowerCase.split('/').last
}
