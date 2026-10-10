package org.llm4s.zio

import java.util.concurrent.TimeUnit

import org.llm4s.agent.testkit.Fixtures
import org.llm4s.error.{ LLMError, SimpleError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  UserMessage
}
import org.llm4s.types.Result
import zio.{ Chunk, Promise, Ref, ZIO, durationInt }
import zio.stream.ZStream
import zio.test.*

/**
 * Real-concurrency behaviour of the streaming bridge. Every wait is a latch, a Promise or a spin on
 * a condition; the only timeouts are safety nets that a correct implementation never reaches.
 */
object LLMClientZConcurrencySpec extends ZIOSpecDefault {
  import Fixtures.*

  private def stream(client: LLMClient): ZStream[Any, LLMError, StreamedChunk] =
    LLMClientZ(client).streamComplete(conversation)

  private def ids(n: Int): Chunk[String] = Chunk.fromIterable((0 until n).map(i => s"c$i"))

  private def await(latch: java.util.concurrent.CountDownLatch): ZIO[Any, Nothing, Boolean] =
    ZIO.attemptBlocking(latch.await(DeadlineSeconds, TimeUnit.SECONDS)).orDie

  private def blockedOn(counting: Counting): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking(awaitBlocked(counting)).orDie

  val spec =
    suite("LLMClientZ concurrency")(
      // ---- backpressure ----------------------------------------------------------------------
      test("blocks the producer when the bounded queue is full and resumes when the consumer drains") {
        val counting = new Counting(100000)
        for {
          gate <- Promise.make[Nothing, Unit]
          fiber <- stream(counting.client)
            .tap(c => ZIO.when(c.id == "c0")(gate.await))
            .runCollect
            .fork
          _        <- blockedOn(counting)
          finished <- ZIO.succeed(counting.finished.get())
          entered  <- ZIO.succeed(counting.entered.get())
          _        <- gate.succeed(())
          out      <- fiber.join
        } yield assertTrue(!finished) &&
          // Capacity is 64; scheduling lets the producer run ahead occasionally, so the bound is
          // generous. An unbounded buffer lets all 100000 through.
          assertTrue(entered <= 10000) &&
          assertTrue(out.map(_.id) == ids(100000)) &&
          assertTrue(counting.finished.get())
      },
      // ---- early termination -----------------------------------------------------------------
      test("interrupts the producer exactly once and releases its thread on early take") {
        val parks = new ParksAfterFirst
        for {
          taken  <- stream(parks.client).take(1).runCollect
          exited <- await(parks.exited)
        } yield assertTrue(taken.map(_.id) == Chunk("c1")) &&
          assertTrue(exited) &&
          assertTrue(parks.interruptions.get() == 1) &&
          assertTrue(parks.client.streamCalls.get() == 1)
      },
      test("terminates and interrupts a producer blocked on a full queue when the consumer stops early") {
        val counting = new Counting(100000)
        for {
          gate  <- Promise.make[Nothing, Unit]
          fiber <- stream(counting.client).tap(_ => gate.await).take(1).runCollect.fork
          _     <- blockedOn(counting)
          fin0  <- ZIO.succeed(counting.finished.get())
          _     <- gate.succeed(())
          out   <- fiber.join
          ex    <- await(counting.exited)
        } yield assertTrue(!fin0) &&
          assertTrue(out.map(_.id) == Chunk("c0")) &&
          assertTrue(ex) &&
          assertTrue(counting.interrupted.get()) &&
          assertTrue(!counting.finished.get())
      },
      test("interrupts the producer when the stream is stopped by interruptWhen (timeout path)") {
        val parks = new ParksAfterFirst
        for {
          stop <- Promise.make[LLMError, Unit]
          out  <- stream(parks.client).tap(_ => stop.succeed(())).interruptWhen(stop).runCollect
          ex   <- await(parks.exited)
        } yield assertTrue(out.size <= 1) && assertTrue(ex) && assertTrue(parks.interruptions.get() == 1)
      },
      // ---- consumer failure / interruption -----------------------------------------------------
      test("propagates a consumer error and interrupts the producer parked mid-call") {
        val parks = new ParksAfterFirst
        for {
          err <- stream(parks.client).mapZIO(_ => ZIO.fail(SimpleError("consumer failed"))).runDrain.flip
          ex  <- await(parks.exited)
        } yield assertTrue(err == SimpleError("consumer failed")) &&
          assertTrue(ex) &&
          assertTrue(parks.interruptions.get() == 1) &&
          assertTrue(parks.client.streamCalls.get() == 1)
      },
      test("interrupts the producer when the consuming fiber is interrupted") {
        val parks = new ParksAfterFirst
        for {
          fiber <- stream(parks.client).runDrain.fork
          _     <- await(parks.started)
          exit  <- fiber.interrupt
          ex    <- await(parks.exited)
        } yield assertTrue(exit.isInterrupted) && assertTrue(ex) && assertTrue(parks.interruptions.get() == 1)
      },
      test("interrupts a producer blocked on a full queue when the consuming fiber is interrupted") {
        val counting = new Counting(100000)
        for {
          gate  <- Promise.make[Nothing, Unit]
          fiber <- stream(counting.client).tap(_ => gate.await).runDrain.fork
          _     <- blockedOn(counting)
          fin0  <- ZIO.succeed(counting.finished.get())
          exit  <- fiber.interrupt
          ex    <- await(counting.exited)
        } yield assertTrue(!fin0) &&
          assertTrue(exit.isInterrupted) &&
          assertTrue(ex) &&
          assertTrue(counting.interrupted.get())
      },
      // ---- producer failure ---------------------------------------------------------------------
      suite("producer failure")(
        List(0, 1, 10, 200).flatMap { n =>
          List(
            test(s"emits $n chunks then fails with the LLMError when the call returns Left (no hang)") {
              val counting = new Counting(n, () => boom)
              stream(counting.client).either.runCollect.map { out =>
                assertTrue(out.size == n + 1) &&
                assertTrue(out.take(n).map(_.map(_.id)) == ids(n).map(Right(_))) &&
                assertTrue(out.last == Left(SimpleError("boom")))
              }
            },
            test(s"emits $n chunks then dies with a thrown non-LLM exception, unchanged") {
              val failure = new IllegalArgumentException("provider blew up")
              val client = new Scripted(onChunk => {
                (0 until n).foreach(i => onChunk(chunk(i)))
                throw failure
              })
              for {
                seen <- Ref.make(Chunk.empty[String])
                exit <- stream(client).tap(c => seen.update(_ :+ c.id)).runDrain.exit
                got  <- seen.get
              } yield assertTrue(got == ids(n)) &&
                assertTrue(exit.causeOption.flatMap(_.dieOption).contains(failure))
            }
          )
        }*
      ),
      // ---- shapes -----------------------------------------------------------------------------------
      test("completes for an empty stream") {
        stream(new Counting(0).client).runCollect.map(out => assertTrue(out.isEmpty))
      },
      test("emits a single chunk") {
        stream(new Counting(1).client).runCollect.map(out => assertTrue(out.map(_.id) == Chunk("c0")))
      },
      test("streams a very large number of chunks in order without blowing the stack or heap") {
        val total    = 200000
        val counting = new Counting(total)
        stream(counting.client)
          .runFold((0, true)) { case ((n, ok), c) => (n + 1, ok && c.id == s"c$n") }
          .map { case (count, inOrder) =>
            assertTrue(count == total) && assertTrue(inOrder) && assertTrue(counting.returned.get() == total)
          }
      },
      test("is re-runnable: every run gets its own queue and its own producer call") {
        val counting = new Counting(300)
        val s        = stream(counting.client)
        s.runCollect.zipPar(s.runCollect).map { case (a, b) =>
          assertTrue(a.map(_.id) == ids(300)) &&
          assertTrue(b.map(_.id) == ids(300)) &&
          assertTrue(counting.client.streamCalls.get() == 2)
        }
      },
      // ---- concurrency ------------------------------------------------------------------------------
      test("serves many fibers on one client without crossing streams") {
        val perStream = 500
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
            val key = c.messages.head.content
            Right(
              Completion(id = "id", created = 0L, content = key, model = "m", message = AssistantMessage(Some(key)))
            )
          }
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = {
            val key = c.messages.head.content
            (0 until perStream).foreach(i => onChunk(StreamedChunk(id = s"$key-$i", content = Some(key))))
            Right(completion)
          }
          def getContextWindow(): Int     = 4096
          def getReserveCompletion(): Int = 256
        }
        val z = LLMClientZ(client)
        ZIO
          .foreachPar((0 until 64).toList) { k =>
            val conv = Conversation(Seq(UserMessage(s"k$k")))
            for {
              chunks <- z.streamComplete(conv).runCollect
              done   <- z.complete(conv)
            } yield (k, chunks, done)
          }
          .map { results =>
            assertTrue(results.size == 64) &&
            assertTrue(results.forall { case (k, chunks, done) =>
              chunks.map(_.id) == Chunk.fromIterable((0 until perStream).map(i => s"k$k-$i")) &&
              done.content == s"k$k"
            })
          }
      },
      // ---- complete ---------------------------------------------------------------------------------
      test("complete maps Left to the LLMError and a thrown exception to a defect") {
        val failure = new IllegalStateException("kaboom")
        for {
          left   <- LLMClientZ(new Scripted(onComplete = () => boom)).complete(conversation).flip
          thrown <- LLMClientZ(new Scripted(onComplete = () => throw failure)).complete(conversation).exit
        } yield assertTrue(left == SimpleError("boom")) &&
          assertTrue(thrown.causeOption.flatMap(_.dieOption).contains(failure))
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
}
