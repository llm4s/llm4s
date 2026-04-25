package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.ProviderKind

private[llm4s] object ProviderCapabilitiesRegistry:

  def forKind(kind: ProviderKind): Result[ProviderCapabilities] =
    registry
      .get(kind)
      .toRight(ConfigurationError(s"No provider capabilities registered for provider '${kind.toString}'"))

  private val registry: Map[ProviderKind, ProviderCapabilities] = Map(
    ProviderKind.OpenAI     -> ProviderCapabilities.OpenAI,
    ProviderKind.OpenRouter -> ProviderCapabilities.OpenRouter,
    ProviderKind.Azure      -> ProviderCapabilities.Azure,
    ProviderKind.Anthropic  -> ProviderCapabilities.Anthropic,
    ProviderKind.Ollama     -> ProviderCapabilities.Ollama,
    ProviderKind.Zai        -> ProviderCapabilities.Zai,
    ProviderKind.Gemini     -> ProviderCapabilities.Gemini,
    ProviderKind.DeepSeek   -> ProviderCapabilities.DeepSeek,
    ProviderKind.Cohere     -> ProviderCapabilities.Cohere,
    ProviderKind.Mistral    -> ProviderCapabilities.Mistral,
  )
