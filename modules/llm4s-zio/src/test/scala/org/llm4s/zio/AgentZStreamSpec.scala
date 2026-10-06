package org.llm4s.zio

import org.llm4s.agent.graph.{
  Checkpointer,
  Commit,
  EventRecord,
  GraphRuntime,
  InMemoryCheckpointer,
  RunEvent,
  StoredCheckpoint,
  StreamEvent,
  ThreadId
}
import org.llm4s.error.{ CancelledError, NetworkError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.types.Result
import zio.{ Chunk, ZIO, durationInt }
import zio.test.*

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

object AgentZStreamSpec extends ZIOSpecDefault {

  private def completion(text: String): Completion =
    Completion(id = "id", created = 0L, content = text, model = "m", message = AssistantMessage(Some(text)))

  private def answering(text: String): LLMClient =
    new Fixtures.Scripted(_ => Right(completion(text)), () => Right(completion(text)))

  /** A store that refuses every commit carrying a `RunCompleted`: the run ends with no terminal event. */
  final private class NoTerminal extends Checkpointer {
    private val underlying = InMemoryCheckpointer()
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (!commit.events.exists(_.event == RunEvent.RunCompleted)) underlying.commit(threadId, commit)
      else Left(ProcessingError("store", "store down"))
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)
  }

  private def durableEvents(items: Chunk[AgentStreamItem]): Vector[RunEvent] =
    items.toVector.collect { case AgentStreamItem.Event(StreamEvent.Durable(r)) => r.event }

  val spec = suite("AgentZ.stream")(
    test("emits the run's events, then Done with its result") {
      AgentZ(Fixtures.agentOf(answering("hello"))()).stream(ThreadId("z1"), "hi").runCollect.map { items =>
        val doneOk = items.last match {
          case AgentStreamItem.Done(r) => r.answer == Some("hello")
          case _                       => false
        }
        val eventsOk = items.init.forall { case AgentStreamItem.Event(_) => true; case _ => false }
        val durable  = durableEvents(items)
        assertTrue(
          doneOk,
          eventsOk,
          durable.head == RunEvent.RunStarted(None, None),
          durable.last == RunEvent.RunCompleted
        )
      }
    },
    test("fails with the run's error") {
      val client = new Fixtures.Scripted(
        _ => Left(NetworkError("down", None, "http://x")),
        () => Left(NetworkError("down", None, "http://x"))
      )
      AgentZ(Fixtures.agentOf(client)())
        .stream(ThreadId("z2"), "hi")
        .runCollect
        .flip
        .map(e => assertTrue(Fixtures.causeOf(e).isInstanceOf[NetworkError]))
    },
    test("fails at once for a refused start") {
      AgentZ(Fixtures.agentOf(answering("x"))())
        .stream(ThreadId("z3"), "  ")
        .runCollect
        .flip
        .map(e => assertTrue(e.isInstanceOf[ValidationError]))
    },
    test("ends, rather than hangs, when the run ends without a terminal event") {
      val agent = Fixtures.agentOf(answering("hello"))(_.withRuntime(GraphRuntime(NoTerminal())))
      AgentZ(agent)
        .stream(ThreadId("z4"), "hi")
        .runCollect
        .flip
        .timeoutFail(new RuntimeException("stream hung"))(30.seconds)
        .map(e => assertTrue(e.toString.contains("store down")))
    },
    test("interrupting the stream cancels the run, releasing the subscription blocked in the buffer") {
      val calls    = new AtomicInteger(0)
      val sent     = new CountDownLatch(1)
      val unparked = new CountDownLatch(1)
      // the first call floods the subscription with more live text than the buffer and the subscription's
      // queue hold - so its dispatcher blocks in the buffer's listener - then parks until interrupted
      val client = new Fixtures.Scripted(
        onChunk =>
          if (calls.getAndIncrement() == 0) {
            (0 until 3000).foreach(i => onChunk(Fixtures.chunk(i)))
            sent.countDown()
            if (Fixtures.parkUntilInterrupted()) unparked.countDown()
            Left(CancelledError("test"))
          } else Right(completion("recovered")),
        () => Right(completion("recovered"))
      )
      val runtime  = GraphRuntime.inMemory()
      val agent    = Fixtures.agentOf(client)(_.withRuntime(runtime).withStreaming())
      val threadId = ThreadId("z5")
      for {
        first <- AgentZ(agent)
          .stream(threadId, "hi")
          .tap(_ => ZIO.attemptBlocking(sent.await(Fixtures.DeadlineSeconds, TimeUnit.SECONDS)).orDie)
          .take(1)
          .runCollect
          .timeoutFail(new RuntimeException("hung"))(60.seconds)
        wasUnparked <- ZIO.attemptBlocking(unparked.await(Fixtures.PromptSeconds, TimeUnit.SECONDS))
        released    <- ZIO.attemptBlocking(Fixtures.awaitCondition(runtime.liveSubscriptions(threadId) == 0))
        recovered   <- AgentZ(agent).recover(threadId)
      } yield assertTrue(first.size == 1, wasUnparked, released, recovered.answer == Some("recovered"))
    },
    test("streamRecover streams a recovered run to Done") {
      val calls = new AtomicInteger(0)
      val client = new Fixtures.Scripted(
        _ => if (calls.getAndIncrement() == 0) Left(NetworkError("down", None, "x")) else Right(completion("back")),
        () => if (calls.getAndIncrement() == 0) Left(NetworkError("down", None, "x")) else Right(completion("back"))
      )
      val agentZ   = AgentZ(Fixtures.agentOf(client)())
      val threadId = ThreadId("z6")
      for {
        failed <- agentZ.stream(threadId, "hi").runDrain.either
        items  <- agentZ.streamRecover(threadId).runCollect
      } yield {
        val doneOk = items.last match {
          case AgentStreamItem.Done(r) => r.answer == Some("back")
          case _                       => false
        }
        assertTrue(failed.isLeft, doneOk, durableEvents(items).last == RunEvent.RunCompleted)
      }
    }
  )
}
