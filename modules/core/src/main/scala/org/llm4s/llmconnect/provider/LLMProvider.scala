package org.llm4s.llmconnect.provider

/**
 * Enumeration of supported LLM providers.
 *
 * Defines the available language model service providers that can be used
 * with llm4s. Each provider has specific configuration requirements and
 * API characteristics.
 *
 * @see [[org.llm4s.llmconnect.config.ProviderConfig]] for provider-specific configuration
 */
sealed trait LLMProvider {

  /** Returns the lowercase string identifier for this provider */
  def name: String = this match {
    case LLMProvider.OpenAI     => "openai"
    case LLMProvider.Azure      => "azure"
    case LLMProvider.Anthropic  => "anthropic"
    case LLMProvider.OpenRouter => "openrouter"
    case LLMProvider.Ollama     => "ollama"
    case LLMProvider.Zai        => "zai"
    case LLMProvider.Gemini     => "gemini"
    case LLMProvider.DeepSeek   => "deepseek"
    case LLMProvider.Cohere     => "cohere"
  }
}

/**
 * Companion object providing LLM provider instances and utilities.
 */
object LLMProvider {

  /**
   * OpenAI provider for GPT models.
   *
   * Supports GPT-4, GPT-3.5-turbo, and other OpenAI models.
   * Requires an OpenAI API key.
   */
  case object OpenAI extends LLMProvider

  /**
   * Azure OpenAI Service provider.
   *
   * Provides access to OpenAI models via Azure cloud infrastructure.
   * Requires Azure-specific configuration including endpoint and API version.
   */
  case object Azure extends LLMProvider

  /**
   * Anthropic provider for Claude models.
   *
   * Supports Claude 3.5 Sonnet, Claude 3 Opus, and other Anthropic models.
   * Features extended thinking capability for complex reasoning tasks.
   */
  case object Anthropic extends LLMProvider

  /**
   * OpenRouter provider for unified model access.
   *
   * Provides access to multiple LLM providers through a single API.
   * Supports reasoning effort parameter for compatible models.
   */
  case object OpenRouter extends LLMProvider

  /**
   * Ollama provider for local model inference.
   *
   * Runs models locally without external API dependencies.
   * Supports various open-source models like Llama, Mistral, etc.
   */
  case object Ollama extends LLMProvider

  /**
   * Z.ai (ZhipuAI) provider for GLM models.
   *
   * Provides access to GLM-4 series models via OpenAI-compatible API.
   * Supports GLM-4.7 and GLM-4.5-air models.
   */
  case object Zai extends LLMProvider

  /**
   * Google Gemini provider for Gemini models.
   *
   * Supports Gemini 2.0, 1.5 Pro, 1.5 Flash and other Gemini models.
   * Features large context windows (up to 1M+ tokens) and multimodal capabilities.
   * Requires a Google AI API key.
   */
  case object Gemini extends LLMProvider

  /**
   * DeepSeek provider for DeepSeek models.
   *
   * Supports DeepSeek-Chat (V3), DeepSeek-Reasoner (R1) and other DeepSeek models.
   * Features reasoning capability for complex tasks.
   * Requires a DeepSeek API key.
   */
  case object DeepSeek extends LLMProvider

  /**
   * Cohere provider for Command models.
   *
   * Minimal v1 support: non-streaming chat/text generation only.
   */
  case object Cohere extends LLMProvider

  /** All available providers */
  val all: Seq[LLMProvider] = Seq(OpenAI, Azure, Anthropic, OpenRouter, Ollama, Zai, Gemini, DeepSeek, Cohere)

  /**
   * Parses a provider name string to an [[LLMProvider]].
   *
   * Matching is case-insensitive. `"google"` is accepted as an alias for
   * `Gemini` for compatibility with the `LLM_MODEL=google/...` environment
   * variable format.
   *
   * @param name Provider name; see [[all]] for the canonical set of names.
   * @return `Some(provider)` for recognised names; `None` for unrecognised input.
   */
  def fromName(name: String): Option[LLMProvider] = name.toLowerCase match {
    case "openai"            => Some(OpenAI)
    case "azure"             => Some(Azure)
    case "anthropic"         => Some(Anthropic)
    case "openrouter"        => Some(OpenRouter)
    case "ollama"            => Some(Ollama)
    case "zai"               => Some(Zai)
    case "gemini" | "google" => Some(Gemini)
    case "deepseek"          => Some(DeepSeek)
    case "cohere"            => Some(Cohere)
    case _                   => None
  }
}
