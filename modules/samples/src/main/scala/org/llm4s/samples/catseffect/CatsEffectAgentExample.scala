package org.llm4s.samples.catseffect

import cats.effect.{ IO, IOApp }
import org.llm4s.agent.AgentContext
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.toolapi.ToolRegistry

/**
 * Demonstrates cats-effect / fs2 integration with llm4s.
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.catseffect.CatsEffectAgentExample"
 * }}}
 *
 * Required environment:
 *   LLM_MODEL=openai/gpt-4o  (or any supported provider)
 *   OPENAI_API_KEY=sk-...
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
        agentIO = client.agent()
        state <- agentIO.run(
                   query = "What day is it today?",
                   tools = ToolRegistry.empty,
                   context = AgentContext.Default
                 )
        _ <- IO.println(s"Agent: ${state.conversation.messages.last}")
      } yield ()
    }
}
