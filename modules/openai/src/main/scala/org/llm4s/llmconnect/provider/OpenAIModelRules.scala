package org.llm4s.llmconnect.provider

import org.llm4s.model.{ ModelCapabilities, ModelRegistryService, RequestTransformer }

/**
 * OpenAI's request rules that follow from model naming rather than from registry data.
 *
 * The o-series reasoning models take no system message, no native streaming, temperature 1
 * only and no sampling penalties, whatever the registry says; they and gpt-5 take
 * `max_completion_tokens` in place of `max_tokens`. These were core's `DefaultRequestTransformer`
 * defaults until they moved here, so other providers' clients no longer apply them.
 */
private[llm4s] object OpenAIModelRules {

  /** The request transformer `OpenAIClient` uses: the registry's capabilities, adjusted by [[adjust]]. */
  def transformer(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.adjusted(service)(adjust)

  /** Applies the o-series constraints on top of `capabilities`; other models pass through. */
  def adjust(modelId: String, capabilities: ModelCapabilities): ModelCapabilities =
    if (isOSeriesModel(modelId))
      capabilities
        .withSupportsReasoning(capabilities.supportsReasoning.orElse(Some(true)))
        .withSupportsNativeStreaming(Some(false))
        .withSupportsSystemMessages(Some(false))
        .withTemperatureConstraint(Some((1.0, 1.0)))
        .withDisallowedParams(Some(Set("top_p", "presence_penalty", "frequency_penalty", "logprobs")))
    else capabilities

  /** Whether the model takes `max_completion_tokens` instead of `max_tokens`. */
  def requiresMaxCompletionTokens(modelId: String): Boolean = {
    val normalized = modelId.toLowerCase
    isOSeriesModel(modelId) || normalized.contains("gpt-5") || normalized.contains("gpt5")
  }

  /** An o-series reasoning model, by name, bare or vendor-prefixed (`openai/o1`). */
  def isOSeriesModel(modelId: String): Boolean = {
    val normalized = modelId.toLowerCase
    normalized.startsWith("o1") || normalized.startsWith("o3") ||
    normalized.contains("/o1") || normalized.contains("/o3")
  }
}
