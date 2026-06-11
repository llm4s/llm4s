package org.llm4s.samples.zio

import org.llm4s.agent.AgentContext
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.zio.LLMClientZ
import zio.{ ZIO, ZIOAppDefault }

/**
 * Demonstrates ZIO integration with llm4s.
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.zio.ZIOAgentExample"
 * }}}
 *
 * Required environment:
 *   LLM_MODEL=openai/gpt-4o  (or any supported provider)
 *   OPENAI_API_KEY=sk-...
 */
object ZIOAgentExample extends ZIOAppDefault {

  def run: ZIO[Any, Any, Any] =
    (for {
      client <- ZIO.service[LLMClientZ]

      // Direct completion — blocking call runs on ZIO's blocking pool
      completion <- client.complete(Conversation(Seq(UserMessage("What is 2 + 2?"))))
      _          <- ZIO.debug(s"Completion: ${completion.content}")

      // Streaming — chunks flow as ZStream elements, collected here
      chunks <- client
        .streamComplete(Conversation(Seq(UserMessage("Count to 5 slowly."))))
        .runCollect
      _ <- ZIO.debug(chunks.map(_.content.getOrElse("")).mkString)

      // Agent with tool support
      agentZ = client.agent()
      state <- agentZ.run(
        query = "What day is it today?",
        tools = ToolRegistry.empty,
        context = AgentContext.Default
      )
      _ <- ZIO.debug(s"Agent: ${state.conversation.messages.last}")
    } yield ()).provide(LLMClientZ.layer)
}
