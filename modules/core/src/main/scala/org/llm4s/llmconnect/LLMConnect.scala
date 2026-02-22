package org.llm4s.llmconnect

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.provider._
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result

/**
 * Constructs an [[LLMClient]] from provider configuration.
 *
 * Provider selection is determined entirely by the runtime type of the
 * [[ProviderConfig]] supplied: an [[AnthropicConfig]] produces an Anthropic
 * client, an [[OpenAIConfig]] produces an OpenAI or OpenRouter client (the
 * latter when `baseUrl` contains `"openrouter.ai"`), and so on. Azure uses
 * [[OpenAIClient]] internally — [[AzureConfig]] carries the deployment
 * endpoint and API-version fields that OpenAI does not require.
 *
 * @example
 * {{{
 * for {
 *   cfg    <- Llm4sConfig.provider()
 *   client <- LLMConnect.getClient(cfg)
 * } yield client
 * }}}
 *
 * @see [[org.llm4s.config.Llm4sConfig.provider]] to load configuration from environment variables
 * @see [[LLMClient]] for the conversation and completion API
 */
object LLMConnect {

  private def buildClient(config: ProviderConfig, metrics: MetricsCollector): Result[LLMClient] =
    config match {
      case cfg: OpenAIConfig =>
        if (cfg.baseUrl.contains("openrouter.ai"))
          OpenRouterClient(cfg, metrics)
        else OpenAIClient(cfg, metrics)
      case cfg: AzureConfig =>
        OpenAIClient(cfg, metrics)
      case cfg: AnthropicConfig =>
        AnthropicClient(cfg, metrics)
      case cfg: OllamaConfig =>
        OllamaClient(cfg, metrics)
      case cfg: ZaiConfig =>
        ZaiClient(cfg, metrics)
      case cfg: GeminiConfig =>
        GeminiClient(cfg, metrics)
      case cfg: DeepSeekConfig =>
        DeepSeekClient(cfg, metrics)
      case cfg: CohereConfig =>
        CohereClient(cfg, metrics)
    }

  // ---- Config-driven construction -----------------------------------------

  /**
   * Constructs an [[LLMClient]], routing to the correct provider based on the
   * runtime type of `config` and recording call statistics to `metrics`.
   *
   * The dispatch is exhaustive — every [[ProviderConfig]] subtype is handled.
   * Returns `Left` only if the underlying client constructor fails (for example,
   * if the HTTP client library throws during initialisation).
   *
   * @param config  Provider configuration; the concrete subtype determines which
   *                client is built. For OpenRouter, supply an [[OpenAIConfig]]
   *                whose `baseUrl` contains `"openrouter.ai"`.
   * @param metrics Receives per-call latency and token-usage events.
   *                Use [[org.llm4s.metrics.MetricsCollector.noop]] when no metrics backend is needed.
   */
  def getClient(
    config: ProviderConfig,
    metrics: MetricsCollector
  ): Result[LLMClient] =
    buildClient(config, metrics)

  /**
   * Constructs an [[LLMClient]] without recording call statistics.
   *
   * Suitable for applications that do not integrate with a metrics backend.
   * Switch to the two-argument overload when per-call latency or token-usage
   * data is needed (e.g. for Prometheus or Micrometer).
   *
   * @param config Provider configuration; the concrete subtype determines which client is built.
   */
  def getClient(config: ProviderConfig): Result[LLMClient] =
    buildClient(config, MetricsCollector.noop)

  // ---- Provider-explicit construction (validates provider/config pairing) -

  /**
   * Constructs an [[LLMClient]], verifying at runtime that `provider` and
   * `config` are consistent with each other.
   *
   * Returns `Left` in two situations: the provider/config pair is mismatched
   * (yields a [[org.llm4s.error.ConfigurationError]]), or the underlying client
   * constructor fails during initialisation. Use this overload when the provider
   * is resolved dynamically from user input or external config and you want an
   * explicit error on mismatch rather than silent wrong routing.
   *
   * @param provider The expected provider; must match the runtime type of `config`.
   * @param config   Provider configuration corresponding to `provider`.
   * @param metrics  Receives per-call latency and token-usage events.
   *                 Use [[org.llm4s.metrics.MetricsCollector.noop]] when no metrics backend is needed.
   * @return the constructed client, or a [[org.llm4s.error.ConfigurationError]] when
   *         `provider` and `config` describe different providers, or an
   *         [[org.llm4s.error.UnknownError]] if client initialisation throws.
   */
  def getClient(
    provider: LLMProvider,
    config: ProviderConfig,
    metrics: MetricsCollector
  ): Result[LLMClient] =
    (provider, config) match {
      case (LLMProvider.OpenAI, cfg: OpenAIConfig)       => OpenAIClient(cfg, metrics)
      case (LLMProvider.OpenRouter, cfg: OpenAIConfig)   => OpenRouterClient(cfg, metrics)
      case (LLMProvider.Azure, cfg: AzureConfig)         => OpenAIClient(cfg, metrics)
      case (LLMProvider.Anthropic, cfg: AnthropicConfig) => AnthropicClient(cfg, metrics)
      case (LLMProvider.Ollama, cfg: OllamaConfig)       => OllamaClient(cfg, metrics)
      case (LLMProvider.Zai, cfg: ZaiConfig)             => ZaiClient(cfg, metrics)
      case (LLMProvider.Gemini, cfg: GeminiConfig)       => GeminiClient(cfg, metrics)
      case (LLMProvider.DeepSeek, cfg: DeepSeekConfig)   => DeepSeekClient(cfg, metrics)
      case (LLMProvider.Cohere, cfg: CohereConfig)       => CohereClient(cfg, metrics)
      case (prov, wrongCfg) =>
        val cfgType = wrongCfg.getClass.getSimpleName
        val msg     = s"Invalid config type $cfgType for provider $prov"
        Left(ConfigurationError(msg))
    }

  /**
   * Constructs an [[LLMClient]], verifying provider/config consistency,
   * without recording call statistics.
   *
   * @param provider The expected provider; must match the runtime type of `config`.
   * @param config   Provider configuration corresponding to `provider`.
   * @return the constructed client, or a [[org.llm4s.error.ConfigurationError]] when
   *         `provider` and `config` describe different providers, or an
   *         [[org.llm4s.error.UnknownError]] if client initialisation throws.
   */
  def getClient(
    provider: LLMProvider,
    config: ProviderConfig
  ): Result[LLMClient] =
    getClient(provider, config, MetricsCollector.noop)
}
