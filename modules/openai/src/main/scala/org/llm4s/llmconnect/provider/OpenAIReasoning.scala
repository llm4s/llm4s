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
   */
  def support(model: String, registry: ModelRegistryService): Support =
    registry.lookup(model) match {
      case Right(meta) if openAIProviders.contains(meta.provider.toLowerCase) =>
        if (meta.capabilities.supportsReasoning.contains(true)) Support.Reasoning else Support.NonReasoning
      case _ =>
        if (namedLikeReasoningModel(model)) Support.Reasoning else Support.Unknown
    }

  /**
   * OpenAI's reasoning-model naming, for models the registry does not know: an `o<digit>`
   * model (`o1`, `o3-mini`, `o4-mini`), a `gpt-<n>` model from gpt-5 on (`gpt-5.6-terra`,
   * `gpt-6-astra`), or an open-weight `gpt-oss` model. A router prefix such as `openai/` is
   * ignored. The generation is one digit, so Azure's `gpt-35-turbo` is not mistaken for one.
   */
  def namedLikeReasoningModel(model: String): Boolean = {
    val name = baseName(model)
    name.matches("o\\d.*") || name.startsWith("gpt-oss") || name.matches("gpt-[5-9](\\D.*)?")
  }

  /**
   * Whether the model rejects sampling parameters (`temperature`, `top_p`, the penalties).
   * OpenAI's own reasoning models do; the open-weight gpt-oss models, served by other hosts,
   * accept them.
   */
  def restrictsSampling(model: String): Boolean = !baseName(model).startsWith("gpt-oss")

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
