package org.llm4s.llmconnect

import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Constructs an [[LLMClient]] from provider configuration.
 *
 * Which client is built is decided by the [[org.llm4s.llmconnect.spi.ProviderRegistry]]:
 * the config names a provider through
 * [[org.llm4s.llmconnect.config.ProviderConfig.providerId]], and that provider's
 * [[org.llm4s.llmconnect.spi.ProviderDescriptor]] builds its own client. `LLMConnect`
 * itself knows no provider names, so a provider supplied by another module is reached
 * the same way a built-in one is.
 *
 * Pass a different registry with `using` to resolve against a custom set of providers;
 * with none in scope, [[org.llm4s.llmconnect.spi.ProviderRegistry.default]] is used.
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
    ModelRegistryService,
    ProviderRegistry
  ): Result[LLMClient] =
    summon[ProviderRegistry].get(config.providerId).flatMap(_.buildClient(config, options))

  def fromConfig(
    config: ProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    buildClient(config, options)

  // ---- Config-driven construction -----------------------------------------

  /**
   * Constructs an [[LLMClient]] for the provider `config` names, recording call
   * statistics to `metrics`.
   *
   * A config whose provider is not registered yields a
   * [[org.llm4s.error.ConfigurationError]] listing the providers that are; `Left`
   * is also returned if the underlying client constructor fails (for example, if
   * the HTTP client library throws during initialisation).
   *
   * @param config  Provider configuration; its
   *                `providerId` selects the client. For OpenRouter, supply an
   *                `OpenAIConfig` whose `baseUrl` contains `"openrouter.ai"`.
   * @param metrics Receives per-call latency and token-usage events.
   *                Use [[org.llm4s.metrics.MetricsCollector.noop]] when no metrics backend is needed.
   */
  def getClient(
    config: ProviderConfig,
    metrics: MetricsCollector
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
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
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    fromConfig(config, options)

  /**
   * Constructs an [[LLMClient]] without recording call statistics.
   *
   * Suitable for applications that do not integrate with a metrics backend.
   * Switch to the two-argument overload when per-call latency or token-usage
   * data is needed (e.g. for Prometheus or Micrometer).
   *
   * @param config Provider configuration; its `providerId` selects the client.
   */
  def getClient(config: ProviderConfig)(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
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
   * @param provider The provider to build; `config` must be the config type it expects.
   * @param config   Provider configuration corresponding to `provider`.
   * @param metrics  Receives per-call latency and token-usage events.
   *                 Use [[org.llm4s.metrics.MetricsCollector.noop]] when no metrics backend is needed.
   * @return the constructed client, or a [[org.llm4s.error.ConfigurationError]] when
   *         `provider` and `config` describe different providers, or an
   *         [[org.llm4s.error.UnknownError]] if client initialisation throws.
   */
  def getClient(
    provider: ProviderId,
    config: ProviderConfig,
    metrics: MetricsCollector
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    getClient(provider, config, LlmClientOptions(metrics = metrics))

  /**
   * Constructs an [[LLMClient]], verifying provider/config consistency, using
   * explicit runtime options.
   */
  def getClient(
    provider: ProviderId,
    config: ProviderConfig,
    options: LlmClientOptions
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    fromProvider(provider, config, options)

  def fromProvider(
    provider: ProviderId,
    config: ProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    summon[ProviderRegistry].get(provider).flatMap(_.buildClient(config, options))

  /**
   * Constructs an [[LLMClient]], verifying provider/config consistency,
   * without recording call statistics.
   *
   * @param provider The provider to build; `config` must be the config type it expects.
   * @param config   Provider configuration corresponding to `provider`.
   * @return the constructed client, or a [[org.llm4s.error.ConfigurationError]] when
   *         `provider` and `config` describe different providers, or an
   *         [[org.llm4s.error.UnknownError]] if client initialisation throws.
   */
  def getClient(
    provider: ProviderId,
    config: ProviderConfig
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    getClient(provider, config, LlmClientOptions.default)
}
