package org.llm4s.javaapi

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.llmconnect.config.{
  EmbeddingModelConfig,
  EmbeddingProviderConfig,
  ModelDimensionRegistry,
  ProviderConfig
}
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
    } yield new JLlmClient(client)
    LlmResult.from(result)
  }

  /**
   * Creates a [[JLlmClient]] from an explicit [[ProviderConfig]].  Useful
   * when the caller constructs the config programmatically rather than relying
   * on environment variables.
   */
  def createClient(config: ProviderConfig): LlmResult[JLlmClient] =
    if (config == null) LlmResult.failure(ValidationError.required("config"))
    else createClientFrom(config)

  private def createClientFrom(config: ProviderConfig): LlmResult[JLlmClient] = {
    val result = for {
      registry <- Llm4sConfig.modelRegistryService()
      client   <- LLMConnect.getClient(config)(using registry)
    } yield new JLlmClient(client)
    LlmResult.from(result)
  }

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
    LlmResult.from(Llm4sConfig.embeddings().flatMap(embeddingClientFor))

  /** The client for the provider and config `llm4s.embeddings` selected, its dimensions as the provider declares them. */
  private def embeddingClientFor(selected: (String, EmbeddingProviderConfig)): Result[JEmbeddingClient] = {
    val (provider, config) = selected
    for {
      dimensions <- ModelDimensionRegistry.getDimension(provider, config.model)
      registry   <- Llm4sConfig.modelRegistryService()
      client     <- EmbeddingClient.from(provider, config)(using registry)
    } yield new JEmbeddingClient(client, EmbeddingModelConfig(config.model, dimensions))
  }

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
    new JAgent((if (streaming) builder.withStreaming() else builder).build())
  }

  /**
   * Wraps an `Agent` built with `Agent.builder`, for what the factories above do not set: its
   * middleware (guardrails, approvals), handoffs, system prompt or runtime.
   */
  def wrapAgent(agent: Agent): JAgent = {
    Objects.requireNonNull(agent, "agent must not be null")
    new JAgent(Right(agent))
  }
}
