package org.llm4s.testkit

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.util.Try

class InterruptionChecksSpec extends AnyFlatSpec with Matchers with ProviderModuleChecks:

  /** Calls `url` with Llm4sHttpClient; streams one chunk per line received. */
  final private class HttpStub(url: String, honour: Boolean) extends LLMClient:
    private val http = Llm4sHttpClient.create()

    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      val r: Result[Completion] =
        http.post(url, Map.empty, "{}", 30.seconds).flatMap(_ => Left(SimpleError("unexpected")))
      if honour then r
      else
        r.left.map { _ =>
          Thread.interrupted()
          SimpleError("swallowed")
        }

    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      http
        .postStream(url, Map.empty, "{}", 30.seconds)
        .flatMap { response =>
          // the body read fails with an IOException when the thread is interrupted
          val read = Try {
            val reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body))
            Iterator.continually(reader.readLine()).takeWhile(_ != null).filter(_.nonEmpty).foreach { line =>
              onChunk(StreamedChunk(id = "s", content = Some(line)))
            }
          }
          Left(read.fold(e => SimpleError(e.toString), _ => SimpleError("stream ended")))
        }
        .left
        .map(e => if Thread.currentThread().isInterrupted then CancelledError("stub.stream") else e)

    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100

  "assertCancelsWhenInterrupted" should "pass for a client that honours interruption" in {
    LocalProviderTestServer.withServer("/x")(LocalProviderTestServer.holdOpen) { base =>
      assertCancelsWhenInterrupted(HttpStub(s"$base/x", honour = true))
    }
  }

  it should "fail for a client that swallows interruption" in {
    LocalProviderTestServer.withServer("/x")(LocalProviderTestServer.holdOpen) { base =>
      a[TestFailedException] should be thrownBy assertCancelsWhenInterrupted(HttpStub(s"$base/x", honour = false))
    }
  }

  "assertCancelsStreamWhenInterrupted" should "pass after the first chunk is delivered" in {
    val handler: HttpExchange => Unit = LocalProviderTestServer.streamThenHold(_, "data: one\n\n")
    LocalProviderTestServer.withServer("/s")(handler) { base =>
      assertCancelsStreamWhenInterrupted(HttpStub(s"$base/s", honour = true))
    }
  }

  /** A client whose every call waits to be interrupted, then does `afterInterrupt` with the flag cleared. */
  final private class Misbehaving(afterInterrupt: () => Result[Completion]) extends LLMClient:
    private def waitThen(): Result[Completion] =
      CancelledError.catchInterrupt(Thread.sleep(30_000)): Unit
      afterInterrupt()

    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = waitThen()
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      waitThen()
    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100

  private def failureOf(check: => Any): String =
    intercept[TestFailedException](check).getMessage

  private def completion =
    Completion("c", 0L, "late", "m", AssistantMessage("late"))

  "assertCancelsWhenInterrupted" should "say so when a client clears the interrupt flag" in {
    failureOf(assertCancelsWhenInterrupted(Misbehaving(() => Left(CancelledError("stub"))))) should include(
      "cleared the thread's interrupt flag"
    )
  }

  it should "say so when an interrupted client returns a result" in {
    failureOf(assertCancelsWhenInterrupted(Misbehaving(() => Right(completion)))) should include(
      "returned Right("
    )
  }

  it should "say so when an interrupted client throws" in {
    failureOf(assertCancelsWhenInterrupted(Misbehaving(() => throw new IllegalStateException("boom")))) should include(
      "threw instead of returning a Result"
    )
  }

  "assertCancelsStreamWhenInterrupted" should "say so when no chunk arrives before the server stalls" in {
    LocalProviderTestServer.withServer("/s")(LocalProviderTestServer.holdOpen) { base =>
      failureOf(assertCancelsStreamWhenInterrupted(HttpStub(s"$base/s", honour = true))) should include(
        "delivered no chunk"
      )
    }
  }

  "withServer" should "stop promptly while a handler is still holding a request open" in {
    val started = System.nanoTime()
    LocalProviderTestServer.withServer("/x")(LocalProviderTestServer.holdOpen) { base =>
      val t = Thread.ofVirtual().start(() => Llm4sHttpClient.create().get(s"$base/x", timeout = 30.seconds): Unit)
      Thread.sleep(200)
      t.interrupt()
    }
    (System.nanoTime() - started).nanos should be < 5.seconds
  }
