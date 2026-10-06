package org.llm4s.samples.zio

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.samples.util.AgentResults
import org.llm4s.zio.{ AgentStreamItem, LLMClientZ }
import zio.{ ZIO, ZIOAppDefault }

/**
 * An agent turn as a ZStream: text deltas print as they arrive, then the turn's result.
 * Interrupting the stream cancels the run.
 *
 * To run: sbt "samples/runMain org.llm4s.samples.zio.AgentStreamZIOExample"
 */
object AgentStreamZIOExample extends ZIOAppDefault:

  def run: ZIO[Any, Any, Any] =
    (for
      client <- ZIO.service[LLMClientZ]
      agent  <- client.agent("agent-stream-zio-example")(_.withStreaming())
      _ <- agent
        .stream(ThreadId("agent-stream-zio"), "Explain monads in three sentences.")
        .tap {
          case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => ZIO.succeed(print(d.text))
          case _                                               => ZIO.unit
        }
        .collect { case AgentStreamItem.Done(result) => result }
        .runForeach(result => ZIO.debug(s"\n\nDone: ${AgentResults.answerOrStatus(result)}"))
    yield ()).provide(LLMClientZ.layer)
