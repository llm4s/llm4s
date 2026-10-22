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
 * The client comes from the default named provider, which `samples/src/main/resources/application.conf` sets to
 * `ollama-local`. To use OpenAI instead, add a section in the git-ignored `application.local.conf` beside it:
 * {{{
 * llm4s {
 *   providers {
 *     openai-main {
 *       provider = "openai"
 *       model = "gpt-4o-mini"
 *     }
 *   }
 * }
 * }}}
 * then select it and give the key (`llm4s-openai` binds `OPENAI_API_KEY` to the provider's key):
 * {{{
 * export OPENAI_API_KEY=sk-... LLM4S_PROVIDER=openai-main
 * }}}
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
