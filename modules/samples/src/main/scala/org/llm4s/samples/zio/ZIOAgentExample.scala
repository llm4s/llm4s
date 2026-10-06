package org.llm4s.samples.zio

import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.samples.util.AgentResults
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
      agentZ <- client.agent("zio-agent-example")()
      result <- agentZ.run("What day is it today?")
      _      <- ZIO.debug(s"Agent: ${AgentResults.answerOrStatus(result)}")
    } yield ()).provide(LLMClientZ.layer)
}
