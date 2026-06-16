package org.llm4s.java

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig

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
  def createClient(config: ProviderConfig): LlmResult[JLlmClient] = {
    val result = for {
      registry <- Llm4sConfig.modelRegistryService()
      client   <- LLMConnect.getClient(config)(using registry)
    } yield new JLlmClient(client)
    LlmResult.from(result)
  }

  /**
   * Wraps a [[JLlmClient]] in a [[JAgent]] ready to accept natural-language
   * queries and call tools.
   */
  def createAgent(client: JLlmClient): JAgent =
    new JAgent(new Agent(client.underlying))
}
