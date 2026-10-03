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

  "withServer" should "stop promptly while a handler is still holding a request open" in {
    val started = System.nanoTime()
    LocalProviderTestServer.withServer("/x")(LocalProviderTestServer.holdOpen) { base =>
      val t = Thread.ofVirtual().start(() => Llm4sHttpClient.create().get(s"$base/x", timeout = 30.seconds): Unit)
      Thread.sleep(200)
      t.interrupt()
    }
    (System.nanoTime() - started).nanos should be < 5.seconds
  }
