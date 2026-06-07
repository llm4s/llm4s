package org.llm4s.llmconnect

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.provider._
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderKind
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
 *   registry <- Llm4sConfig.modelRegistryService()
 *   cfg      <- Llm4sConfig.defaultProvider()
 *   client   <- LLMConnect.getClient(cfg)(using registry)
 * } yield client
 * }}}
 *
 * @see [[org.llm4s.config.Llm4sConfig.defaultProvider]] to load the configured default named provider
 * @see [[LLMClient]] for the conversation and completion API
 */
object LLMConnect {

  private def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    val metrics         = options.metrics
    val exchangeLogging = options.exchangeLogging
    config match {
      case cfg: OpenAIConfig =>
        if (cfg.baseUrl.contains("openrouter.ai"))
          OpenRouterClient(cfg, metrics, exchangeLogging)
        else OpenAIClient(cfg, metrics, exchangeLogging)
      case cfg: AzureConfig =>
        OpenAIClient(cfg, metrics, exchangeLogging)
      case cfg: AnthropicConfig =>
        AnthropicClient(cfg, metrics, exchangeLogging)
      case cfg: OllamaConfig =>
        OllamaClient(cfg, metrics, exchangeLogging)
      case cfg: ZaiConfig =>
        ZaiClient(cfg, metrics, exchangeLogging)
      case cfg: GeminiConfig =>
        GeminiClient(cfg, metrics, exchangeLogging)
      case cfg: DeepSeekConfig =>
        DeepSeekClient(cfg, metrics, exchangeLogging)
      case cfg: CohereConfig =>
        CohereClient(cfg, metrics, exchangeLogging)
      case cfg: MistralConfig =>
        MistralClient(cfg, metrics, exchangeLogging)
    }

  def fromConfig(
    config: ProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default
  )(using ModelRegistryService): Result[LLMClient] =
    buildClient(config, options)

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
  )(using ModelRegistryService): Result[LLMClient] =
    fromConfig(config, LlmClientOptions(metrics = metrics))

  /**
   * Constructs an [[LLMClient]] using explicit runtime options.
   *
   * This is the preferred extension point for optional client behaviors such as
   * metrics collection and provider exchange logging.
   */
  def getClient(
    config: ProviderConfig,
    options: LlmClientOptions
  )(using ModelRegistryService): Result[LLMClient] =
    fromConfig(config, options)

  /**
   * Constructs an [[LLMClient]] without recording call statistics.
   *
   * Suitable for applications that do not integrate with a metrics backend.
   * Switch to the two-argument overload when per-call latency or token-usage
   * data is needed (e.g. for Prometheus or Micrometer).
   *
   * @param config Provider configuration; the concrete subtype determines which client is built.
   */
  def getClient(config: ProviderConfig)(using ModelRegistryService): Result[LLMClient] =
    fromConfig(config)

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
    provider: ProviderKind,
    config: ProviderConfig,
    metrics: MetricsCollector
  )(using ModelRegistryService): Result[LLMClient] =
    getClient(provider, config, LlmClientOptions(metrics = metrics))

  /**
   * Constructs an [[LLMClient]], verifying provider/config consistency, using
   * explicit runtime options.
   */
  def getClient(
    provider: ProviderKind,
    config: ProviderConfig,
    options: LlmClientOptions
  )(using ModelRegistryService): Result[LLMClient] =
    fromProvider(provider, config, options)

  def fromProvider(
    provider: ProviderKind,
    config: ProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default
  )(using ModelRegistryService): Result[LLMClient] =
    val metrics         = options.metrics
    val exchangeLogging = options.exchangeLogging
    (provider, config) match {
      case (ProviderKind.OpenAI, cfg: OpenAIConfig)       => OpenAIClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.OpenRouter, cfg: OpenAIConfig)   => OpenRouterClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Azure, cfg: AzureConfig)         => OpenAIClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Anthropic, cfg: AnthropicConfig) => AnthropicClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Ollama, cfg: OllamaConfig)       => OllamaClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Zai, cfg: ZaiConfig)             => ZaiClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Gemini, cfg: GeminiConfig)       => GeminiClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.DeepSeek, cfg: DeepSeekConfig)   => DeepSeekClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Cohere, cfg: CohereConfig)       => CohereClient(cfg, metrics, exchangeLogging)
      case (ProviderKind.Mistral, cfg: MistralConfig)     => MistralClient(cfg, metrics, exchangeLogging)
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
    provider: ProviderKind,
    config: ProviderConfig
  )(using ModelRegistryService): Result[LLMClient] =
    getClient(provider, config, LlmClientOptions.default)
}
