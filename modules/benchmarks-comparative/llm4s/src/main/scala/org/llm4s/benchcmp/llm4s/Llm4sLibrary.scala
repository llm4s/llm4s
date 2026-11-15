package org.llm4s.benchcmp.llm4s

import org.llm4s.agent.Agent
import org.llm4s.benchcmp.harness.ChatLibrary
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result

/**
 * llm4s: the generic `openai-compatible` provider (`llm4s-openai-compatible`) through `LLMConnect`, and an `Agent`
 * from `llm4s-agent` with one `ToolBuilder` tool for the tool loop. No tracing, no retries (the defaults).
 */
final class Llm4sLibrary(baseUrl: String) extends ChatLibrary:

  private val client: LLMClient = Llm4sLibrary.orThrow(Llm4sLibrary.client(baseUrl))

  private val agent: Agent = Llm4sLibrary.orThrow(
    for
      lookup <- ToolBuilder[Map[String, Any], String](
        "lookup",
        "Looks up the value stored under a key",
        Schema
          .`object`[Map[String, Any]]("Lookup parameters")
          .withProperty(Schema.property("key", Schema.string("The key to look up")))
      ).withHandler(args => args.getString("key").map(key => s"value-$key")).buildSafe()
      agent <- Agent.builder("bench", client).withTools(new ToolRegistry(Seq(lookup))).build()
    yield agent
  )

  override def chat(prompt: String): String =
    Llm4sLibrary.orThrow(client.complete(Conversation(Seq(UserMessage(prompt))))).content

  override def stream(prompt: String): Int =
    var chunks                         = 0
    val onChunk: StreamedChunk => Unit = chunk => if chunk.content.exists(_.nonEmpty) then chunks += 1
    Llm4sLibrary.orThrow(client.streamComplete(Conversation(Seq(UserMessage(prompt))), CompletionOptions(), onChunk))
    chunks

  override def toolAgent(prompt: String): String =
    val result = Llm4sLibrary.orThrow(agent.run(prompt))
    // A completed run's thread stays in the agent's in-memory runtime until it is forgotten; a server would do the same.
    Llm4sLibrary.orThrow(agent.forget(result.threadId))
    result.answer.getOrElse("")

  override def close(): Unit = client.close()

object Llm4sLibrary:

  /** The client the benchmarks and the cold-start process build: a config, the model registry, `LLMConnect`. */
  def client(baseUrl: String): Result[LLMClient] =
    ModelRegistryService.default().flatMap { registry =>
      given ModelRegistryService = registry
      LLMConnect.getClient(OpenAICompatibleConfig("bench-model", baseUrl, apiKey = Some(Secret("bench-key"))))
    }

  private[llm4s] def orThrow[A](result: Result[A]): A =
    result.fold((e: LLMError) => throw new IllegalStateException(e.formatted), identity)
