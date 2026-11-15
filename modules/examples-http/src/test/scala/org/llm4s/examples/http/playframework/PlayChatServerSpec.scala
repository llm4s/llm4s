package org.llm4s.examples.http.playframework

import org.llm4s.examples.http.http4s.TestAgents.ParksAfterFirstChunk
import org.llm4s.llmconnect.LLMClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.inject.bind
import play.core.server.Server

import java.io.{ BufferedReader, InputStreamReader }
import java.net.{ Socket, URI }
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.util.Using
import scala.util.Using.Releasable

/** The Play app as a user runs it: Play's Pekko HTTP server on an ephemeral port, called over real sockets. */
class PlayChatServerSpec extends AnyFlatSpec with Matchers {

  private given Releasable[Server] = _.stop()

  /** `test` against the app on an ephemeral port, its provider `client` unless the fake one; stopped afterwards. */
  private def serving[A](client: Option[LLMClient] = None, heartbeat: String = "15 seconds")(test: Int => A): A = {
    val builder = PlayChatServer
      .builder(fake = client.isEmpty)
      .configure("examples.chat.heartbeat" -> heartbeat)
    val app    = client.fold(builder)(c => builder.overrides(bind[LLMClient].toInstance(c))).build()
    val server = PlayChatServer.start(app, "127.0.0.1", 0)
    Using.resource(server)(s => test(s.httpPort.getOrElse(fail("the server has no HTTP port"))))
  }

  private val http = HttpClient.newHttpClient()

  private def post(port: Int, path: String, body: String): HttpResponse[String] =
    http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  "The Play app" should "answer POST /chat with the final answer as JSON" in {
    val response = serving()(port => post(port, "/chat", """{"message": "hello"}"""))
    response.statusCode shouldBe 200
    val json = ujson.read(response.body)
    json("answer").str shouldBe "You said: hello"
    json("status").str shouldBe "Completed"
  }

  it should "stream POST /chat/stream as server-sent events that add up to the answer, then end with done" in {
    val response = serving()(port => post(port, "/chat/stream", """{"message": "hello"}"""))
    response.statusCode shouldBe 200
    response.headers.firstValue("Content-Type").orElse("") should startWith("text/event-stream")
    val received = response.body
      .split("\n\n")
      .toVector
      .map(_.linesIterator.toVector)
      .collect { case Vector(name, data) => name.stripPrefix("event: ") -> ujson.read(data.stripPrefix("data: ")) }
    received.last._1 shouldBe "done"
    received.last._2("answer").str shouldBe "You said: hello"
    val deltas = received.init
    deltas.map(_._1).toSet shouldBe Set("delta")
    deltas.size should be > 1
    deltas.map(_._2("text").str).mkString shouldBe "You said: hello"
  }

  it should "cancel the run, interrupting the provider call, when the client disconnects mid-stream" in {
    val provider = new ParksAfterFirstChunk
    serving(Some(provider), heartbeat = "50 ms") { port =>
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
      lines.exists(_.contains("\"text\":\"partial\"")) shouldBe true
      socket.close()
      withClue("the provider call was never interrupted: ")(
        provider.interrupted.await(30, TimeUnit.SECONDS) shouldBe true
      )
    }
  }
}
