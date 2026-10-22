package org.llm4s.samples.catseffect

import cats.effect.{ IO, IOApp }
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.samples.util.AgentResults

/**
 * Demonstrates cats-effect / fs2 integration with llm4s.
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.catseffect.CatsEffectAgentExample"
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
object CatsEffectAgentExample extends IOApp.Simple {

  def run: IO[Unit] =
    LLMClientIO.resource[IO].use { client =>
      for {
        // Direct completion — blocking call runs on the blocking pool
        completion <- client.complete(
          Conversation(Seq(UserMessage("What is 2 + 2?")))
        )
        _ <- IO.println(s"Completion: ${completion.content}")

        // Streaming — chunks flow as fs2 Stream elements
        _ <- IO.println("Streaming response:")
        _ <- client
          .streamComplete(Conversation(Seq(UserMessage("Count to 5 slowly."))))
          .evalMap(chunk => IO.print(chunk.content.getOrElse("")))
          .compile
          .drain
        _ <- IO.println("")

        // Agent with tool support
        agentIO <- client.agent("cats-effect-agent-example")()
        result  <- agentIO.run("What day is it today?")
        _       <- IO.println(s"Agent: ${AgentResults.answerOrStatus(result)}")
      } yield ()
    }
}
