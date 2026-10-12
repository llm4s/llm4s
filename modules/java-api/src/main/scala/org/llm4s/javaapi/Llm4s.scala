package org.llm4s.javaapi

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.{
  EmbeddingModelConfig,
  EmbeddingProviderConfig,
  ModelDimensionRegistry,
  ProviderConfig
}
import org.llm4s.metrics.MetricsCollector
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

import java.util.Objects

/**
 * Entry-point factory for Java callers.
 *
 * All methods return [[LlmResult]] so Java code never imports Scala `Either`
 * or deals with implicit/given parameters directly. The Scala-level
 * `ModelRegistryService` given is resolved internally and passed explicitly.
 *
 * === Quick-start (Java) ===
 * {{{
 * LlmResult<JLlmClient> r = Llm4s.createDefaultClient();
 * if (r.isSuccess()) {
 *     JLlmClient client = r.get();
 *     client.complete("Hello").ifSuccess(System.out::println);
 * }
 * }}}
 *
 * === A client configured in code ===
 * The providers this module brings each have a builder, so a client needs no `application.conf` and no Scala type:
 * [[openAi]], [[anthropic]], [[gemini]], [[ollama]], [[openAiCompatible]] (vLLM, LM Studio, LiteLLM, a gateway) and
 * [[azureOpenAi]]. Every other provider - one from its own module, such as `llm4s-bedrock` - takes its config object
 * through [[createClient]], or a section of `application.conf` through [[createDefaultClient]].
 * {{{
 * LlmResult<JLlmClient> r = Llm4s.openAi()
 *     .apiKey(apiKey)   // read however your application keeps its secrets
 *     .model("gpt-4o-mini")
 *     .build();
 * }}}
 */
object Llm4s {

  /**
   * Creates a [[JLlmClient]] from the default provider configured via
   * environment variables (e.g. `LLM_MODEL`, `OPENAI_API_KEY`).
   */
  def createDefaultClient(): LlmResult[JLlmClient] = {
    val result = for {
      registry <- Llm4sConfig.modelRegistryService()
      config   <- Llm4sConfig.defaultProvider()
      client   <- LLMConnect.getClient(config)(using registry)
    } yield wrapClient(client)
    FacadeInternals.result(result)
  }

  /** A builder for an OpenAI client: `apiKey` and `model` are required (#1489). */
  def openAi(): OpenAiClientBuilder = new OpenAiClientBuilder(ClientSettings.EMPTY)

  /** A builder for an Anthropic client: `apiKey` and `model` are required (#1489). */
  def anthropic(): AnthropicClientBuilder = new AnthropicClientBuilder(ClientSettings.EMPTY)

  /** A builder for a client of the Google Gemini API: `apiKey` and `model` are required (#1489). */
  def gemini(): GeminiClientBuilder = new GeminiClientBuilder(ClientSettings.EMPTY)

  /** A builder for an Ollama client: `model` is required, and the server is `http://localhost:11434` unless set (#1489). */
  def ollama(): OllamaClientBuilder = new OllamaClientBuilder(ClientSettings.EMPTY)

  /**
   * A builder for a client of any server that speaks OpenAI's chat-completions API - vLLM, LM Studio, LiteLLM, a
   * gateway: `baseUrl` and `model` are required, the API key optional (#1489).
   */
  def openAiCompatible(): OpenAiCompatibleClientBuilder = new OpenAiCompatibleClientBuilder(ClientSettings.EMPTY)

  /** A builder for an Azure OpenAI client: `endpoint`, `apiKey` and `model` (the deployment) are required (#1489). */
  def azureOpenAi(): AzureOpenAiClientBuilder = new AzureOpenAiClientBuilder(ClientSettings.EMPTY)

  /**
   * Creates a [[JLlmClient]] from an explicit [[ProviderConfig]].  Useful
   * when the caller constructs the config programmatically rather than relying
   * on environment variables.
   */
  def createClient(config: ProviderConfig): LlmResult[JLlmClient] =
    FacadeInternals.createClient(config, MetricsCollector.noop)

  /**
   * [[createClient]] whose client reports every call (request outcome, duration, tokens and cost) to `metrics`, for
   * example a `MicrometerMetrics` from `llm4s-observability-micrometer` over the application's `MeterRegistry`, or a
   * `PrometheusMetrics` from `llm4s-observability-prometheus`. The Spring Boot starter uses it to attach the
   * application's `MetricsCollector` bean. A null `metrics` records nothing; a null `config` is a failed result.
   */
  def createClient(config: ProviderConfig, metrics: MetricsCollector): LlmResult[JLlmClient] =
    FacadeInternals.createClient(config, metrics)

  /**
   * Wraps a Scala [[LLMClient]] you built yourself - a `ReliableClient` around a provider's client, your own
   * implementation, or a test double - in a [[JLlmClient]]. Closing the wrapper closes `client`.
   *
   * @throws NullPointerException when `client` is `null`
   */
  def wrapClient(client: LLMClient): JLlmClient =
    new JLlmClient(FacadeToken.INSTANCE, Objects.requireNonNull(client, "client must not be null"))

  /**
   * Creates a [[JEmbeddingClient]] for the embedding model configured as chat providers are, in the application's
   * `application.conf`: `llm4s.embeddings.model`, `provider/model` (for example `openai/text-embedding-3-small`), which
   * `EMBEDDING_MODEL` sets; the provider's key comes from its `llm4s.embeddings.<provider>` block or its vendor
   * variable, such as `OPENAI_API_KEY`.
   *
   * Does not throw and sends no request: no model configured, an embedding provider that is not on the classpath, a
   * missing key, or a model whose dimensions its provider module does not declare is a failed [[LlmResult]] of kind
   * `CONFIGURATION`, whose message says what to set.
   */
  def createDefaultEmbeddingClient(): LlmResult[JEmbeddingClient] =
    FacadeInternals.result(Llm4sConfig.embeddings().flatMap(embeddingClientFor))

  /** The client for the provider and config `llm4s.embeddings` selected, its dimensions as the provider declares them. */
  private def embeddingClientFor(selected: (String, EmbeddingProviderConfig)): Result[JEmbeddingClient] = {
    val (provider, config) = selected
    for {
      dimensions <- ModelDimensionRegistry.getDimension(provider, config.model)
      registry   <- Llm4sConfig.modelRegistryService()
      client     <- EmbeddingClient.from(provider, config)(using registry)
    } yield wrapEmbeddingClient(client, EmbeddingModelConfig(config.model, dimensions))
  }

  /**
   * Wraps a Scala [[EmbeddingClient]] you built yourself - over your own `EmbeddingProvider`, or a test double - in a
   * [[JEmbeddingClient]] that asks for `model` and reports its dimensions.
   *
   * @throws NullPointerException when either argument is `null`
   */
  def wrapEmbeddingClient(client: EmbeddingClient, model: EmbeddingModelConfig): JEmbeddingClient =
    new JEmbeddingClient(
      FacadeToken.INSTANCE,
      Objects.requireNonNull(client, "client must not be null"),
      Objects.requireNonNull(model, "model must not be null")
    )

  /**
   * Wraps a [[JLlmClient]] in a [[JAgent]] ready to accept natural-language
   * queries, with no tools.
   */
  def createAgent(client: JLlmClient): JAgent =
    createAgent(client, ToolRegistry.empty)

  /**
   * Wraps a [[JLlmClient]] in a [[JAgent]] that can call `tools`. Tools belong
   * to the agent: an agent whose tools cannot be offered together (a name clash,
   * an invalid schema) fails every run with the reason.
   */
  def createAgent(client: JLlmClient, tools: ToolRegistry): JAgent =
    createAgent(client, tools, false)

  /**
   * [[createAgent]] with `tools`, whose model calls stream when `streaming` is set: then a
   * [[JAgent.stream]] of it carries the answer's text deltas (`AgentEvents.TextDelta()`). Without
   * it, the agent calls the model's `complete`, and a stream carries no deltas.
   */
  def createAgent(client: JLlmClient, tools: ToolRegistry, streaming: Boolean): JAgent = {
    Objects.requireNonNull(client, "client must not be null")
    Objects.requireNonNull(tools, "tools must not be null")
    val builder = Agent.builder("assistant", client.underlying).withTools(tools)
    new JAgent(FacadeToken.INSTANCE, (if (streaming) builder.withStreaming() else builder).build())
  }

  /**
   * Wraps an `Agent` built with `Agent.builder`, for what the factories above do not set: its
   * middleware (guardrails, approvals), handoffs, system prompt or runtime.
   */
  def wrapAgent(agent: Agent): JAgent = {
    Objects.requireNonNull(agent, "agent must not be null")
    new JAgent(FacadeToken.INSTANCE, Right(agent))
  }
}
