package org.llm4s.llmconnect.provider

import com.openai.models.{ ReasoningEffort => SdkReasoningEffort }
import org.llm4s.llmconnect.model.ReasoningEffort
import org.llm4s.model.ModelRegistryService

/**
 * Decides whether [[OpenAIClient]] treats a model as an OpenAI reasoning model, and how it
 * maps [[ReasoningEffort]] onto the chat-completions `reasoning_effort` parameter.
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

  /**
   * The `reasoning_effort` value for an llm4s effort level. `ReasoningEffort.None` maps to no
   * parameter at all, leaving the model's own default: OpenAI's lowest accepted value differs by
   * model (`low` for the o-series, `minimal` for gpt-5, `none` from gpt-5.1), and a value the
   * model does not accept is rejected rather than rounded.
   */
  def toSdk(effort: ReasoningEffort): Option[SdkReasoningEffort] = effort match {
    case ReasoningEffort.None   => None
    case ReasoningEffort.Low    => Some(SdkReasoningEffort.LOW)
    case ReasoningEffort.Medium => Some(SdkReasoningEffort.MEDIUM)
    case ReasoningEffort.High   => Some(SdkReasoningEffort.HIGH)
  }

  private def baseName(model: String): String = model.trim.toLowerCase.split('/').last
}
