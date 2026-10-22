package org.llm4s.examples.http.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.llm4s.agent.{ AgentResult, AgentRun }
import org.llm4s.agent.graph.{ EventHandlers, EventType, InterruptId, RunConfig, ThreadId }
import org.llm4s.effect.cats.{ AgentIO, AgentStreamItem, LLMClientIO }
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result

import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport
import scala.concurrent.duration.*

/** Agents and clients the http4s and Tapir apps' specs share: both serve an `AgentIO[IO]`. */
object TestAgents {

  /** The app's agent over `client`. */
  def agentOver(client: LLMClient): AgentIO[IO] =
    Http4sChatServer.agent(LLMClientIO[IO](client)).unsafeRunSync()

  /** An `AgentIO` that fails with a plain exception, as no llm4s effect does. */
  object Failing extends Unsupported {
    override def run(query: String, config: RunConfig): IO[AgentResult] = IO.raiseError(new RuntimeException("boom"))
    override def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[IO, AgentStreamItem] =
      Stream.raiseError[IO](new IllegalStateException())
  }

  /** An `AgentIO` whose stream is quiet for a while, then ends with a real result. */
  object Slow extends Unsupported {
    private lazy val agent = agentOver(ScriptedLLMClient.always(Reply.text("late")))
    override def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[IO, AgentStreamItem] =
      Stream.sleep_[IO](200.millis) ++ agent.stream(threadId, query, config)
  }

  trait Unsupported extends AgentIO[IO] {
    private def no[A]: IO[A]                                   = IO.raiseError(new UnsupportedOperationException)
    def run(query: String, config: RunConfig): IO[AgentResult] = no
    def continueConversation(previous: AgentResult, query: String, config: RunConfig): IO[AgentResult]         = no
    def recover(threadId: ThreadId, config: RunConfig): IO[AgentResult]                                        = no
    def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig): IO[AgentResult] = no
    def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[IO, AgentStreamItem] = Stream.eval(no)
    def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig) =
      Stream.eval(no[AgentStreamItem])
    def streamRecover(threadId: ThreadId, config: RunConfig): Stream[IO, AgentStreamItem] = Stream.eval(no)
    def events[A](run: AgentRun, eventType: EventType[A]): Stream[IO, A]                  = Stream.eval(no)
    def events[R](run: AgentRun, handlers: EventHandlers[R]): Stream[IO, R]               = Stream.eval(no)
    def nestedEvents[R](run: AgentRun, handlers: EventHandlers[R]): Stream[IO, R]         = Stream.eval(no)
  }

  /** Streams one chunk, then parks until its thread is interrupted - which only cancelling the run does. */
  final class ParksAfterFirstChunk extends LLMClient {
    val interrupted = new CountDownLatch(1)

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(CancelledError("complete is not used: the app's agent streams"))

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      onChunk(StreamedChunk(id = "c1", content = Some("partial")))
      val deadline = System.nanoTime() + 60.seconds.toNanos
      while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
        LockSupport.parkNanos(10.millis.toNanos)
      if (Thread.currentThread().isInterrupted) interrupted.countDown()
      Left(CancelledError("stream"))
    }

    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }
}
