package org.llm4s.samples.pekko

import org.apache.pekko.actor.ActorSystem
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.pekko.{ AgentStreamItem, LLMClientPekko, LLMException }
import org.llm4s.samples.util.AgentResults
import org.llm4s.types.Result

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * An agent turn as an Apache Pekko Streams `Source`: text deltas print as they arrive, then the turn's result.
 * Cancelling the stream cancels the run.
 *
 * To run: sbt "samples/runMain org.llm4s.samples.pekko.AgentStreamPekkoExample"
 */
object AgentStreamPekkoExample:

  def main(args: Array[String]): Unit =
    given system: ActorSystem = ActorSystem("agent-stream-pekko-example")
    import system.dispatcher

    val outcome: Result[Unit] =
      for
        registry <- Llm4sConfig.modelRegistryService()
        config   <- Llm4sConfig.defaultProvider()
        client   <- LLMConnect.getClient(config)(using registry)
        agent    <- LLMClientPekko(client).agent("agent-stream-pekko-example")(_.withStreaming())
      yield
        val finished = agent
          .stream(ThreadId("agent-stream-pekko"), "Explain monads in three sentences.")
          .runForeach {
            case AgentStreamItem.Event(AgentEvents.TextDelta(delta)) => print(delta.text)
            case AgentStreamItem.Done(result) => println(s"\n\nDone: ${AgentResults.answerOrStatus(result)}")
            case _                            => ()
          }
          .recover { case e: LLMException => println(s"\n\nFailed: ${e.error.formatted}") }
        Await.result(finished, 5.minutes)
        client.close()

    outcome.left.foreach(error => println(s"Could not start: ${error.formatted}"))
    Await.result(system.terminate(), 30.seconds): Unit
