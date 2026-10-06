package org.llm4s.javaapi

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.toolapi.ToolRegistry

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
  def createAgent(client: JLlmClient, tools: ToolRegistry): JAgent = {
    Objects.requireNonNull(client, "client must not be null")
    Objects.requireNonNull(tools, "tools must not be null")
    new JAgent(Agent.builder("assistant", client.underlying).withTools(tools).build())
  }

  /**
   * Wraps an `Agent` built with `Agent.builder`, for what the factories above do not set: its
   * middleware (guardrails, approvals), handoffs, runtime, or `withStreaming()` - without which a
   * stream carries no text deltas.
   */
  def wrapAgent(agent: Agent): JAgent = {
    Objects.requireNonNull(agent, "agent must not be null")
    new JAgent(Right(agent))
  }
}
