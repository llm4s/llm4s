package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.ProviderId

/** Registry mapping each built-in `ProviderId` to its `ProviderCapabilities` instance. */
private[llm4s] object ProviderCapabilitiesRegistry:

  /**
   * Looks up the `ProviderCapabilities` for the given provider id.
   *
   *  @param id the `ProviderId` to look up
   *  @return `Right(ProviderCapabilities)` when registered, or `Left` with a `ConfigurationError`
   *          naming the ids that are registered
   */
  def forProvider(id: ProviderId): Result[ProviderCapabilities] =
    registry
      .get(id)
      .toRight(
        ConfigurationError(
          s"No provider capabilities registered for provider '${id.asString}'. " +
            s"Registered providers: ${registeredIds.mkString(", ")}"
        )
      )

  /** The provider ids this build knows how to validate, in canonical spelling. */
  def registeredIds: Seq[String] = registry.keys.map(_.asString).toSeq.sorted

  private val registry: Map[ProviderId, ProviderCapabilities] = Map(
    ProviderId("openai")     -> ProviderCapabilities.OpenAI,
    ProviderId("openrouter") -> ProviderCapabilities.OpenRouter,
    ProviderId("requesty")   -> ProviderCapabilities.Requesty,
    ProviderId("azure")      -> ProviderCapabilities.Azure,
    ProviderId("anthropic")  -> ProviderCapabilities.Anthropic,
    ProviderId("ollama")     -> ProviderCapabilities.Ollama,
    ProviderId("zai")        -> ProviderCapabilities.Zai,
    ProviderId("gemini")     -> ProviderCapabilities.Gemini,
    ProviderId("deepseek")   -> ProviderCapabilities.DeepSeek,
    ProviderId("cohere")     -> ProviderCapabilities.Cohere,
    ProviderId("mistral")    -> ProviderCapabilities.Mistral,
    ProviderId("vertexai")   -> ProviderCapabilities.VertexAI,
  )
