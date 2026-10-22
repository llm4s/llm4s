package org.llm4s.examples.http.http4s

import cats.effect.{ IO, Resource }
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import org.http4s.{ Method, Request, ServerSentEvent, Status, Uri }
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.error.CancelledError
import org.llm4s.examples.http.ServerSettings
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ BufferedReader, InputStreamReader }
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.locks.LockSupport
import scala.concurrent.duration.*

/** The app as a user runs it: an Ember server on an ephemeral port, called over real sockets. */
class Http4sChatServerSpec extends AnyFlatSpec with Matchers {

  /** A server over `client` on an ephemeral port, and an HTTP client for it. */
  private def serving(client: LLMClient, heartbeat: FiniteDuration = 15.seconds): Resource[IO, (Uri, Client[IO])] =
    for {
      agent  <- Resource.eval(Http4sChatServer.agent(LLMClientIO[IO](client)))
      server <- Http4sChatServer.resource(agent, ipv4"127.0.0.1", port"0", heartbeat)
      http   <- EmberClientBuilder.default[IO].build
    } yield (server.baseUri, http)

  private def chatRequest(uri: Uri, message: String): Request[IO] =
    Request[IO](Method.POST, uri).withEntity(ujson.Obj("message" -> message).render())

  "The http4s app" should "answer POST /chat with the final answer as JSON" in {
    val (status, body) = serving(ServerSettings.fakeClient)
      .use { case (base, http) =>
        http.run(chatRequest(base / "chat", "hello")).use(r => r.as[String].map(r.status -> _))
      }
      .unsafeRunSync()
    status shouldBe Status.Ok
    val json = ujson.read(body)
    json("answer").str shouldBe "You said: hello"
    json("status").str shouldBe "Completed"
  }

  it should "stream POST /chat/stream as server-sent events that add up to the answer, then end with done" in {
    val received = serving(ServerSettings.fakeClient)
      .use { case (base, http) =>
        http
          .stream(chatRequest(base / "chat" / "stream", "hello"))
          .flatMap(_.body.through(ServerSentEvent.decoder[IO]))
          .collect { case ServerSentEvent(Some(data), Some(name), _, _, _) => name -> ujson.read(data) }
          .compile
          .toVector
      }
      .unsafeRunSync()
    received.last._1 shouldBe "done"
    received.last._2("answer").str shouldBe "You said: hello"
    val deltas = received.init
    deltas.map(_._1).toSet shouldBe Set("delta")
    deltas.size should be > 1
    deltas.map(_._2("text").str).mkString shouldBe "You said: hello"
  }

  it should "cancel the run, interrupting the provider call, when the client disconnects mid-stream" in {
    val provider = new ParksAfterFirstChunk
    serving(provider, heartbeat = 50.millis)
      .use { case (base, _) =>
        IO.blocking {
          val socket = new Socket(base.host.map(_.value).getOrElse("127.0.0.1"), base.port.getOrElse(0))
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
          val sawDelta = lines.exists(_.contains("\"text\":\"partial\""))
          socket.close()
          sawDelta
        }.map(_ shouldBe true) *>
          IO.blocking(provider.interrupted.await(30, TimeUnit.SECONDS))
            .map(withClue("the provider call was never interrupted: ")(_) shouldBe true)
      }
      .unsafeRunSync()
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
