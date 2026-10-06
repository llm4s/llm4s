package org.llm4s.samples.catseffect

import cats.effect.{ IO, IOApp }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.effect.cats.{ AgentStreamItem, LLMClientIO }
import org.llm4s.samples.util.AgentResults

/**
 * An agent turn as an fs2 stream: text deltas print as they arrive, then the turn's result.
 * Interrupting the stream cancels the run.
 *
 * To run: sbt "samples/runMain org.llm4s.samples.catseffect.AgentStreamIOExample"
 */
object AgentStreamIOExample extends IOApp.Simple:

  def run: IO[Unit] =
    LLMClientIO.resource[IO].use { client =>
      for
        agent <- client.agent("agent-stream-io-example")(_.withStreaming())
        items = agent.stream(ThreadId("agent-stream-io"), "Explain monads in three sentences.")
        _ <- items
          .evalTap {
            case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => IO.print(d.text)
            case _                                               => IO.unit
          }
          .collect { case AgentStreamItem.Done(result) => result }
          .evalMap(result => IO.println(s"\n\nDone: ${AgentResults.answerOrStatus(result)}"))
          .compile
          .drain
      yield ()
    }
