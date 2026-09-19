package org.llm4s.reliability

import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

/**
 * Wraps LLM clients with reliability features (retry, circuit breaking, timeouts).
 *
 * There is one entry point per input: a [[org.llm4s.llmconnect.config.ProviderConfig]],
 * which is built into a client and then wrapped, or an
 * [[org.llm4s.llmconnect.LLMClient]] you already have.
 *
 * This replaced seven per-provider factories (`openai`, `anthropic`, ...) which
 * covered only 7 of the 12 built-in providers and could not cover a provider
 * supplied by another module at all. Routing through
 * [[org.llm4s.llmconnect.LLMConnect]] covers every registered provider — see
 * [[https://github.com/llm4s/llm4s/issues/1131 #1131]].
 *
 * Example usage:
 * {{{
 * // A reliable client for whatever provider the config names
 * val client = ReliableProviders.wrap(config)
 *
 * // Or with custom reliability config
 * val aggressive = ReliableProviders.wrap(config, ReliabilityConfig.aggressive)
 * }}}
 */
object ReliableProviders {

  /**
   * Builds the client `config` names and wraps it with reliability features.
   *
   * The provider name used for metrics is the config's own
   * [[org.llm4s.llmconnect.config.ProviderConfig.providerId]].
   *
   * @param config            configuration naming the provider to build
   * @param reliabilityConfig retry/circuit-breaker settings
   * @param metrics           collector receiving both client and reliability events
   * @return Right(ReliableClient), or Left(LLMError) if the provider is not registered
   *         or its client fails to initialise
   */
  def wrap(
    config: ProviderConfig,
    reliabilityConfig: ReliabilityConfig,
    metrics: MetricsCollector
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    LLMConnect
      .getClient(config, metrics)
      .map(client => new ReliableClient(client, config.providerId.asString, reliabilityConfig, Some(metrics)))

  /** Builds and wraps the client `config` names, without metrics. */
  def wrap(
    config: ProviderConfig,
    reliabilityConfig: ReliabilityConfig
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    wrap(config, reliabilityConfig, MetricsCollector.noop)

  /** Builds and wraps the client `config` names, with default reliability settings. */
  def wrap(
    config: ProviderConfig
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    wrap(config, ReliabilityConfig.default, MetricsCollector.noop)

  /**
   * Wrap any existing LLMClient with reliability features.
   *
   * Use this when you already have a configured LLMClient instance.
   *
   * @param client The client to wrap
   * @param providerName Explicit provider name for metrics (e.g., "openai", "anthropic")
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Optional metrics collector
   * @return ReliableClient wrapping the provided client
   */
  def wrap(
    client: LLMClient,
    providerName: String,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: Option[MetricsCollector] = None
  ): LLMClient =
    new ReliableClient(client, providerName, reliabilityConfig, metrics)
}

/**
 * Implicit syntax extensions for adding reliability to existing clients.
 *
 * Import this to add `.withReliability()` method to any LLMClient.
 *
 * Example:
 * {{{
 * import org.llm4s.reliability.ReliabilitySyntax._
 *
 * val client = OpenAIClient(config, metrics).map(_.withReliability("openai"))
 * }}}
 */
object ReliabilitySyntax {

  implicit class LLMClientOps(val client: LLMClient) extends AnyVal {

    /**
     * Wrap this client with default reliability features.
     * Provider name derived from class name (not recommended for production).
     */
    def withReliability(): LLMClient = {
      val providerName = client.getClass.getSimpleName.replace("Client", "").toLowerCase
      new ReliableClient(client, providerName, ReliabilityConfig.default, None)
    }

    /**
     * Wrap this client with reliability, providing explicit provider name (recommended).
     */
    def withReliability(providerName: String): LLMClient =
      new ReliableClient(client, providerName, ReliabilityConfig.default, None)

    /**
     * Wrap this client with custom reliability configuration.
     */
    def withReliability(providerName: String, config: ReliabilityConfig): LLMClient =
      new ReliableClient(client, providerName, config, None)

    /**
     * Wrap this client with custom reliability configuration and metrics.
     */
    def withReliability(providerName: String, config: ReliabilityConfig, metrics: MetricsCollector): LLMClient =
      new ReliableClient(client, providerName, config, Some(metrics))
  }
}
