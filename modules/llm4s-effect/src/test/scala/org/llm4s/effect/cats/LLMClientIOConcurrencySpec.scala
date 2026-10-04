package org.llm4s.effect.cats

import java.util.concurrent.TimeUnit

import cats.effect.{ Deferred, IO }
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Real-concurrency behaviour of the streaming bridge. Every wait is a latch, a Deferred or a spin on
 * a condition; the only timeouts are safety nets that a correct implementation never reaches.
 */
class LLMClientIOConcurrencySpec extends AnyFlatSpec with Matchers {
  import Fixtures.*

  private val Safety = 20.seconds

  // Await on a Future so a hung (uncancellable) stream fails the test instead of hanging the suite.
  private def run[A](io: IO[A]): A = Await.result(io.unsafeToFuture(), Safety)

  private def stream(client: LLMClient) = LLMClientIO[IO](client).streamComplete(conversation)

  private def ids(n: Int): List[String] = (0 until n).map(i => s"c$i").toList

  // ---- backpressure -------------------------------------------------------------------------

  "streamComplete" should "block the producer when the bounded queue is full and resume when the consumer drains" in {
    val counting = new Counting(100000)
    val gate     = Deferred.unsafe[IO, Unit]
    val result = stream(counting.client)
      .evalTap(c => if (c.id == "c0") gate.get else IO.unit)
      .compile
      .toList
      .unsafeToFuture()

    awaitBlocked(counting)
    withClue("producer ran to completion despite a stalled consumer (queue is not bounded): ") {
      counting.finished.get() shouldBe false
    }
    // Capacity is 64 and the producer is typically stopped at 66; fs2/cats-effect scheduling lets it
    // run ahead occasionally (up to ~1500 observed in 1 of 200 runs), so the bound is generous.
    // An unbounded buffer lets all 100000 through.
    counting.entered.get() should be <= 10000

    run(gate.complete(()))
    Await.result(result, Safety).map(_.id) shouldBe ids(100000)
    counting.finished.get() shouldBe true
  }

  // ---- early termination ----------------------------------------------------------------------

  it should "interrupt the producer exactly once and release its thread on early take" in {
    val parks = new ParksAfterFirst
    run(stream(parks.client).take(1).compile.toList).map(_.id) shouldBe List("c1")
    // Structured: the stream does not complete before the producer has been wound down.
    parks.exited.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    parks.interruptions.get() shouldBe 1
    parks.client.streamCalls.get() shouldBe 1
  }

  it should "terminate and interrupt a producer that is blocked on a full queue when the consumer stops early" in {
    val counting = new Counting(100000)
    val gate     = Deferred.unsafe[IO, Unit]
    val result = stream(counting.client)
      .evalTap(_ => gate.get)
      .take(1)
      .compile
      .toList
      .unsafeToFuture()

    awaitBlocked(counting)
    counting.finished.get() shouldBe false
    run(gate.complete(()))
    Await.result(result, Safety).map(_.id) shouldBe List("c0")
    counting.exited.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    counting.interrupted.get() shouldBe true
    counting.finished.get() shouldBe false
  }

  it should "interrupt the producer when fs2 interruptWhen stops the stream (timeout path)" in {
    val parks = new ParksAfterFirst
    val stop  = Deferred.unsafe[IO, Either[Throwable, Unit]]
    val out = run(
      stream(parks.client)
        .evalTap(_ => stop.complete(Right(())).void)
        .interruptWhen(stop)
        .compile
        .toList
    )
    out.size should be <= 1
    parks.exited.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    parks.interruptions.get() shouldBe 1
  }

  // ---- consumer failure / cancellation --------------------------------------------------------

  it should "propagate a consumer error and interrupt the producer that is parked mid-call" in {
    val parks = new ParksAfterFirst
    val outcome = run(
      stream(parks.client)
        .evalTap(_ => IO.raiseError[Unit](new IllegalStateException("consumer failed")))
        .compile
        .drain
        .attempt
    )
    outcome.left.toOption.map(_.getMessage) shouldBe Some("consumer failed")
    parks.exited.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    parks.interruptions.get() shouldBe 1
    parks.client.streamCalls.get() shouldBe 1
  }

  it should "interrupt the producer when the consuming fiber is cancelled" in {
    val parks = new ParksAfterFirst
    val fiber = stream(parks.client).compile.drain.start.unsafeRunSync()
    parks.started.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    run(fiber.cancel)
    run(fiber.join) shouldBe a[Outcome.Canceled[?, ?, ?]]
    parks.exited.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    parks.interruptions.get() shouldBe 1
  }

  it should "interrupt a producer blocked on a full queue when the consuming fiber is cancelled" in {
    val counting = new Counting(100000)
    val gate     = Deferred.unsafe[IO, Unit]
    val fiber    = stream(counting.client).evalTap(_ => gate.get).compile.drain.start.unsafeRunSync()
    awaitBlocked(counting)
    counting.finished.get() shouldBe false
    run(fiber.cancel)
    run(fiber.join) shouldBe a[Outcome.Canceled[?, ?, ?]]
    counting.exited.await(Safety.toSeconds, TimeUnit.SECONDS) shouldBe true
    counting.interrupted.get() shouldBe true
  }

  // ---- producer failure -----------------------------------------------------------------------

  for (n <- List(0, 1, 10, 200)) {
    it should s"emit $n chunks and then fail with LLMException when the call returns Left (no hang)" in {
      val counting = new Counting(n, () => boom)
      val out      = run(stream(counting.client).attempt.compile.toList)
      out.size shouldBe n + 1
      out.take(n).map(_.map(_.id)) shouldBe ids(n).map(Right(_))
      val last = out.last
      last.isLeft shouldBe true
      last.left.toOption.get shouldBe a[LLMException]
      last.left.toOption.get.asInstanceOf[LLMException].error shouldBe SimpleError("boom")
    }

    it should s"emit $n chunks and then surface a thrown non-LLM exception unchanged" in {
      val failure = new IllegalArgumentException("provider blew up")
      val client = new Scripted(onChunk => {
        (0 until n).foreach(i => onChunk(chunk(i)))
        throw failure
      })
      val out = run(stream(client).attempt.compile.toList)
      out.take(n).map(_.map(_.id)) shouldBe ids(n).map(Right(_))
      (out.last.left.toOption.get should be).theSameInstanceAs(failure)
      out.size shouldBe n + 1
    }
  }

  // ---- shapes ---------------------------------------------------------------------------------

  it should "complete for an empty stream" in {
    run(stream(new Counting(0).client).compile.toList) shouldBe Nil
  }

  it should "emit a single chunk" in {
    run(stream(new Counting(1).client).compile.toList).map(_.id) shouldBe List("c0")
  }

  it should "stream a very large number of chunks in order without blowing the stack or heap" in {
    val total    = 200000
    val counting = new Counting(total)
    val (count, inOrder) = run(
      stream(counting.client).compile.fold((0, true)) { case ((n, ok), c) => (n + 1, ok && c.id == s"c$n") }
    )
    count shouldBe total
    inOrder shouldBe true
    counting.returned.get() shouldBe total
  }

  it should "be re-runnable: every run gets its own queue and its own producer call" in {
    val counting = new Counting(300)
    val s        = stream(counting.client)
    val (a, b)   = run((s.compile.toList, s.compile.toList).parTupled)
    a.map(_.id) shouldBe ids(300)
    b.map(_.id) shouldBe ids(300)
    counting.client.streamCalls.get() shouldBe 2
  }

  // ---- concurrency ----------------------------------------------------------------------------

  it should "serve many fibers on one client without crossing streams" in {
    val perStream = 500
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        val key = c.messages.head.content
        Right(Completion(id = "id", created = 0L, content = key, model = "m", message = AssistantMessage(Some(key))))
      }
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = {
        val key = c.messages.head.content
        (0 until perStream).foreach(i => onChunk(StreamedChunk(id = s"$key-$i", content = Some(key))))
        Right(completion)
      }
      def getContextWindow(): Int     = 4096
      def getReserveCompletion(): Int = 256
    }
    val io = LLMClientIO[IO](client)
    val results = run(
      (0 until 64).toList.parTraverse { k =>
        val conv = Conversation(Seq(org.llm4s.llmconnect.model.UserMessage(s"k$k")))
        for {
          chunks <- io.streamComplete(conv).compile.toList
          done   <- io.complete(conv)
        } yield (k, chunks, done)
      }
    )
    results.size shouldBe 64
    results.foreach { case (k, chunks, done) =>
      chunks.map(_.id) shouldBe (0 until perStream).map(i => s"k$k-$i").toList
      done.content shouldBe s"k$k"
    }
  }

  // ---- complete -------------------------------------------------------------------------------

  "complete" should "map Left to LLMException and a thrown exception to the raw throwable" in {
    val left = run(LLMClientIO[IO](new Scripted(onComplete = () => boom)).complete(conversation).attempt)
    left.left.toOption.get shouldBe a[LLMException]
    val failure = new IllegalStateException("kaboom")
    val thrown  = run(LLMClientIO[IO](new Scripted(onComplete = () => throw failure)).complete(conversation).attempt)
    (thrown.left.toOption.get should be).theSameInstanceAs(failure)
  }
}
