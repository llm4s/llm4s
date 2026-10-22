package org.llm4s.examples.http.ziohttp

import org.llm4s.error.CancelledError
import org.llm4s.examples.http.ServerSettings
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import org.llm4s.zio.LLMClientZ
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{ Duration, Scope, ZIO }

import java.io.{ BufferedReader, InputStreamReader }
import java.net.{ Socket, URI }
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.locks.LockSupport
import scala.concurrent.duration.*

import ZioRuntime.unsafeRun

/** The app as a user runs it: a ZIO HTTP server on an ephemeral port, called over real sockets. */
class ZioChatServerSpec extends AnyFlatSpec with Matchers {

  /** A server over `client` on an ephemeral port, for as long as the scope is open; its port. */
  private def serving(client: LLMClient, heartbeat: FiniteDuration = 15.seconds): ZIO[Scope, Throwable, Int] =
    ZioChatServer.agent(LLMClientZ(client)).mapError(e => new IllegalStateException(e.message)).flatMap { agent =>
      ZioChatServer.start(agent, "127.0.0.1", 0, Duration.fromScala(heartbeat))
    }

  /**
   * POSTs `{"message": message}` to `path` with the JDK's HTTP client, and reads the whole response body: an
   * independent client, so the test checks the wire format rather than ZIO HTTP agreeing with itself.
   */
  private def chat(port: Int, path: String, message: String): ZIO[Any, Throwable, (Int, String)] =
    ZIO.attemptBlockingInterrupt {
      val http = HttpClient.newHttpClient()
      val request = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .timeout(java.time.Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(ujson.Obj("message" -> message).render()))
        .build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() -> response.body()
    }

  "The ZIO HTTP app" should "answer POST /chat with the final answer as JSON" in {
    val (status, body) = unsafeRun(serving(ServerSettings.fakeClient).flatMap(chat(_, "/chat", "hello")))
    status shouldBe 200
    val json = ujson.read(body)
    json("answer").str shouldBe "You said: hello"
    json("status").str shouldBe "Completed"
  }

  it should "stream POST /chat/stream as server-sent events that add up to the answer, then end with done" in {
    val (status, body) = unsafeRun(serving(ServerSettings.fakeClient).flatMap(chat(_, "/chat/stream", "hello")))
    status shouldBe 200
    val received = body
      .split("\n\n")
      .toVector
      .map { block =>
        val lines = block.linesIterator.toVector
        lines.find(_.startsWith("event: ")).map(_.stripPrefix("event: ")).getOrElse("") ->
          ujson.read(lines.find(_.startsWith("data: ")).map(_.stripPrefix("data: ")).getOrElse("null"))
      }
    received.last._1 shouldBe "done"
    received.last._2("answer").str shouldBe "You said: hello"
    val deltas = received.init
    deltas.map(_._1).toSet shouldBe Set("delta")
    deltas.size should be > 1
    deltas.map(_._2("text").str).mkString shouldBe "You said: hello"
  }

  it should "cancel the run, interrupting the provider call, when the client disconnects mid-stream" in {
    val provider = new ParksAfterFirstChunk
    val sawDelta = unsafeRun(serving(provider, heartbeat = 50.millis).flatMap { port =>
      ZIO.attemptBlocking {
        val socket = new Socket("127.0.0.1", port)
        socket.setSoTimeout(30000)
        val body = """{"message": "hello"}"""
        val request =
          s"POST /chat/stream HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
            s"Content-Length: ${body.length}\r\n\r\n$body"
        socket.getOutputStream.write(request.getBytes(StandardCharsets.UTF_8))
        socket.getOutputStream.flush()
        val in    = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8))
        val lines = Iterator.continually(in.readLine()).takeWhile(_ != null)
        // The first piece of the answer has arrived, and the provider call is parked waiting for the next.
        val seen = lines.exists(_.contains("\"text\":\"partial\""))
        socket.close()
        seen
      } <* ZIO.attemptBlocking(provider.interrupted.await(30, TimeUnit.SECONDS))
    })
    sawDelta shouldBe true
    withClue("the provider call was never interrupted: ")(provider.interrupted.getCount shouldBe 0L)
  }

  /** Streams one chunk, then parks until its thread is interrupted - which only cancelling the run does. */
  final private class ParksAfterFirstChunk extends LLMClient {
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
