package org.llm4s.reliability

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.provider._
import org.llm4s.llmconnect.config._
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result

/**
 * Convenience methods for wrapping provider clients with reliability features.
 *
 * Provides easy-to-use factory methods for creating reliable versions of
 * each LLM provider client (OpenAI, Anthropic, Gemini, Ollama, OpenRouter, Zai).
 *
 * Example usage:
 * {{{
 * // Create a reliable OpenAI client with default settings
 * val client = ReliableProviders.openai(
 *   OpenAIConfig(
 *     apiKey = "sk-...",
 *     model = "gpt-4o"
 *   )
 * )
 *
 * // Or with custom reliability config
 * val aggressiveClient = ReliableProviders.openai(
 *   OpenAIConfig(apiKey = "sk-...", model = "gpt-4o"),
 *   ReliabilityConfig.aggressive
 * )
 * }}}
 */
object ReliableProviders {

  /**
   * Create a reliable OpenAI client.
   *
   * @param config OpenAI configuration
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping OpenAIClient, or Left(LLMError) on failure
   */
  def openai(
    config: OpenAIConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    OpenAIClient(config, metrics).map(client => new ReliableClient(client, "openai", reliabilityConfig, Some(metrics)))

  /**
   * Create a reliable Azure OpenAI client.
   *
   * @param config Azure OpenAI configuration
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping OpenAIClient, or Left(LLMError) on failure
   */
  def azureOpenAI(
    config: AzureConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    OpenAIClient(config, metrics).map(client =>
      new ReliableClient(client, "azure-openai", reliabilityConfig, Some(metrics))
    )

  /**
   * Create a reliable Anthropic client.
   *
   * @param config Anthropic configuration
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping AnthropicClient, or Left(LLMError) on failure
   */
  def anthropic(
    config: AnthropicConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    AnthropicClient(config, metrics).map(client =>
      new ReliableClient(client, "anthropic", reliabilityConfig, Some(metrics))
    )

  /**
   * Create a reliable Gemini client.
   *
   * @param config Gemini configuration
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping GeminiClient, or Left(LLMError) on failure
   */
  def gemini(
    config: GeminiConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    GeminiClient(config, metrics).map(client => new ReliableClient(client, "gemini", reliabilityConfig, Some(metrics)))

  /**
   * Create a reliable Ollama client.
   *
   * @param config Ollama configuration
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping OllamaClient, or Left(LLMError) on failure
   */
  def ollama(
    config: OllamaConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    OllamaClient(config, metrics).map(client => new ReliableClient(client, "ollama", reliabilityConfig, Some(metrics)))

  /**
   * Create a reliable OpenRouter client.
   *
   * OpenRouter uses OpenAI-compatible configuration.
   *
   * @param config OpenAI configuration (works with OpenRouter)
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping OpenRouterClient, or Left(LLMError) on failure
   */
  def openRouter(
    config: OpenAIConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    OpenRouterClient(config, metrics).map(client =>
      new ReliableClient(client, "openrouter", reliabilityConfig, Some(metrics))
    )

  /**
   * Create a reliable Zai client.
   *
   * @param config Zai configuration
   * @param reliabilityConfig Reliability configuration (default: ReliabilityConfig.default)
   * @param metrics Metrics collector (default: noop)
   * @return Right(ReliableClient) wrapping ZaiClient, or Left(LLMError) on failure
   */
  def zai(
    config: ZaiConfig,
    reliabilityConfig: ReliabilityConfig = ReliabilityConfig.default,
    metrics: MetricsCollector = MetricsCollector.noop
  ): Result[LLMClient] =
    ZaiClient(config, metrics).map(client => new ReliableClient(client, "zai", reliabilityConfig, Some(metrics)))

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
