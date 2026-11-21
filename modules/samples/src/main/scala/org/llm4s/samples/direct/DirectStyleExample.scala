package org.llm4s.samples.direct

import ox.either
import ox.either.ok
import ox.scheduling.Schedule
import ox.timeoutOption
import org.llm4s.error.RateLimitError
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.ox.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * Direct style with `llm4s-ox` (docs/guide/direct-style.md): a streamed completion as an Ox `Flow`, bounded fan-out,
 * a race between providers, a timeout and a retry that honours a rate limit's retry-after.
 *
 * It runs offline: every client is a `ScriptedLLMClient`, so no API key or model server is needed. Swap in
 * `LLMConnect.getClient(provider)` for a real one.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.direct.DirectStyleExample"
 * }}}
 */
object DirectStyleExample extends App {

  private def ask(text: String): Conversation = Conversation(Seq(UserMessage(text)))

  private def lastUserText(conversation: Conversation): String =
    conversation.messages.lastOption.map(_.content).getOrElse("")

  // 1. Stream a reply as an Ox Flow, a few characters per chunk.
  val storyteller = ScriptedLLMClient.always(Reply.text("Once upon a time, a monad met a functor.")).withChunkSize(6)
  print("Streamed: ")
  storyteller.streamFlow(ask("Tell me a story")).textDeltas.runForeach(delta => print(s"[$delta]"))
  println()

  // Stop after three chunks: the provider call is interrupted and has returned before `runToList` does.
  println(s"First three chunks: ${storyteller.streamFlow(ask("Again")).textDeltas.take(3).runToList()}")
  println(s"Accumulated: ${storyteller.streamFlow(ask("Once more")).accumulate.map(_.content)}")

  // 2. Fan out: summarise four chapters, two at a time, then combine them all-or-nothing in an `either` block.
  val summariser = ScriptedLLMClient.respondingTo { case c => Reply.text(s"summary of ${lastUserText(c).take(9)}") }
  val chapters   = (1 to 4).map(i => ask(s"chapter $i: ..."))
  val book: Result[String] = either {
    val parts = summariser.parComplete(chapters, parallelism = 2).map(_.ok().content)
    summariser.complete(ask(parts.mkString("; "))).ok().content
  }
  println(s"Combined: $book")

  // 3. Race two providers under a timeout: the first success wins, the slower call is interrupted.
  val down  = ScriptedLLMClient.always(Reply.failure(RateLimitError("primary", 1.second)))
  val spare = ScriptedLLMClient.always(Reply.text("answer from the spare provider"))
  println(s"Race: ${timeoutOption(5.seconds)(raceProviders(down, spare)(ask("Hi"))).map(_.map(_.content))}")

  // 4. Retry: the first call is rate limited with a 200 ms retry-after, which the retry waits out.
  val flaky = ScriptedLLMClient.sequence(
    Reply.failure(RateLimitError("flaky", 200.millis)),
    Reply.text("answer after one retry")
  )
  val started = System.nanoTime()
  val retried = RetrySchedule.retry(Schedule.fixedInterval(10.millis).maxRetries(3))(flaky.complete(ask("Hi")))
  println(s"Retried: ${retried.map(_.content)} after ${(System.nanoTime() - started) / 1000000} ms")
}
