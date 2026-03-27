package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.ProviderKind

private[config] object ValidatorsRegistry:

  def forKind(kind: ProviderKind): Result[NamedProviderValidator] =
    registry
      .get(kind)
      .toRight(ConfigurationError(s"No validator registered for provider '${kind.toString}'"))

  private val registry: Map[ProviderKind, NamedProviderValidator] = Map(
    ProviderKind.OpenAI     -> NamedProviderValidators.OpenAI,
    ProviderKind.OpenRouter -> NamedProviderValidators.OpenRouter,
    ProviderKind.Azure      -> NamedProviderValidators.Azure,
    ProviderKind.Anthropic  -> NamedProviderValidators.Anthropic,
    ProviderKind.Ollama     -> NamedProviderValidators.Ollama,
    ProviderKind.Zai        -> NamedProviderValidators.Zai,
    ProviderKind.Gemini     -> NamedProviderValidators.Gemini,
    ProviderKind.DeepSeek   -> NamedProviderValidators.DeepSeek,
    ProviderKind.Cohere     -> NamedProviderValidators.Cohere,
    ProviderKind.Mistral    -> NamedProviderValidators.Mistral,
  )
