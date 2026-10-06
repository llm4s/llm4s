package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
import org.llm4s.error.{ NetworkError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class AgentIOStreamSpec extends AnyFlatSpec with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

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

  private def durableEvents(items: Vector[AgentStreamItem]): Vector[RunEvent] =
    items.collect { case AgentStreamItem.Event(StreamEvent.Durable(r)) => r.event }

  "AgentIO.stream" should "emit the run's events, then Done with its result" in {
    val agentIO = AgentIO[IO](Fixtures.agentOf(answering("hello"))())
    val items   = agentIO.stream(ThreadId("f1"), "hi").compile.toVector.unsafeRunSync()
    items.last match {
      case AgentStreamItem.Done(r) => r.answer shouldBe Some("hello")
      case other                   => fail(s"last item was $other")
    }
    items.init.forall { case AgentStreamItem.Event(_) => true; case _ => false } shouldBe true
    durableEvents(items).head shouldBe RunEvent.RunStarted(None, None)
    durableEvents(items).last shouldBe RunEvent.RunCompleted
  }

  it should "fail with the run's error" in {
    val client = new Fixtures.Scripted(
      _ => Left(NetworkError("down", None, "http://x")),
      () => Left(NetworkError("down", None, "http://x"))
    )
    val result = AgentIO[IO](Fixtures.agentOf(client)())
      .stream(ThreadId("f2"), "hi")
      .compile
      .toVector
      .attempt
      .unsafeRunSync()
    result match {
      case Left(e: LLMException) => Fixtures.causeOf(e.error) shouldBe a[NetworkError]
      case other                 => fail(s"expected an LLMException, got $other")
    }
  }

  it should "fail at once for a refused start" in {
    val result = AgentIO[IO](Fixtures.agentOf(answering("x"))())
      .stream(ThreadId("f3"), "  ")
      .compile
      .toVector
      .attempt
      .unsafeRunSync()
    result match {
      case Left(e: LLMException) => e.error shouldBe a[ValidationError]
      case other                 => fail(s"expected an LLMException, got $other")
    }
  }

  it should "end, rather than hang, when the run ends without a terminal event" in {
    val agent = Fixtures.agentOf(answering("hello"))(_.withRuntime(GraphRuntime(NoTerminal())))
    val result = AgentIO[IO](agent)
      .stream(ThreadId("f4"), "hi")
      .compile
      .toVector
      .attempt
      .timeout(30.seconds)
      .unsafeRunSync()
    result match {
      case Left(e: LLMException) => e.error.message should include("store down")
      case other                 => fail(s"expected the run's error as an LLMException, got $other")
    }
  }

  it should "cancel the run when the stream is interrupted, releasing the subscription blocked in the buffer" in {
    val calls    = new AtomicInteger(0)
    val sent     = new CountDownLatch(1)
    val parked   = new CountDownLatch(1)
    val unparked = new CountDownLatch(1)
    // the first call floods the subscription with more live text than the buffer and the subscription's
    // queue hold - so its dispatcher blocks in the buffer's listener - then parks until interrupted
    val client = new Fixtures.Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          (0 until 3000).foreach(i => onChunk(Fixtures.chunk(i)))
          sent.countDown()
          parked.countDown()
          if (Fixtures.parkUntilInterrupted()) unparked.countDown()
          Left(org.llm4s.error.CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime  = GraphRuntime.inMemory()
    val agent    = Fixtures.agentOf(client)(_.withRuntime(runtime).withStreaming())
    val threadId = ThreadId("f5")
    val first = AgentIO[IO](agent)
      .stream(threadId, "hi")
      .evalTap(_ => IO.blocking(sent.await(Fixtures.DeadlineSeconds, TimeUnit.SECONDS)))
      .take(1)
      .compile
      .toVector
      .timeout(60.seconds)
      .unsafeRunSync()
    first should have size 1
    parked.getCount shouldBe 0
    unparked.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true
    // the dispatcher blocked in the buffer was released, so the subscription has ended
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    AgentIO[IO](agent).recover(threadId).unsafeRunSync().answer shouldBe Some("recovered")
  }

  "AgentIO.streamRecover" should "stream a recovered run to Done" in {
    val calls = new AtomicInteger(0)
    val client = new Fixtures.Scripted(
      _ => if (calls.getAndIncrement() == 0) Left(NetworkError("down", None, "x")) else Right(completion("back")),
      () => if (calls.getAndIncrement() == 0) Left(NetworkError("down", None, "x")) else Right(completion("back"))
    )
    val agentIO  = AgentIO[IO](Fixtures.agentOf(client)())
    val threadId = ThreadId("f6")
    agentIO.stream(threadId, "hi").compile.drain.attempt.unsafeRunSync().isLeft shouldBe true
    val items = agentIO.streamRecover(threadId).compile.toVector.unsafeRunSync()
    items.last match {
      case AgentStreamItem.Done(r) => r.answer shouldBe Some("back")
      case other                   => fail(s"last item was $other")
    }
    durableEvents(items).last shouldBe RunEvent.RunCompleted
  }
}
