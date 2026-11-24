package org.llm4s.trace

import com.sun.net.httpserver.HttpServer
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.model.{ AssistantMessage, UsageSummary, UserMessage }
import org.llm4s.security.Secret
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * `LangfuseTracing` against a local HTTP server standing in for Langfuse, over the real JDK client: the request line,
 * headers and body as they go over the wire, and an error response (#2883). Nothing leaves the machine.
 */
class LangfuseOtlpServerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  final private case class Received(method: String, path: String, headers: Map[String, String], body: String)

  private val received         = mutable.Buffer.empty[Received]
  @volatile private var status = 200
  @volatile private var reply  = "{}"

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext(
    "/",
    exchange => {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val headers = exchange.getRequestHeaders.asScala.map { case (k, v) =>
        k.toLowerCase -> v.asScala.mkString(",")
      }.toMap
      received.synchronized(
        received += Received(exchange.getRequestMethod, exchange.getRequestURI.getPath, headers, body)
      )
      val bytes = reply.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    }
  )

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit  = server.stop(0)

  private def base = s"http://127.0.0.1:${server.getAddress.getPort}"

  private def tracing(url: String) =
    new LangfuseTracing(
      url,
      Secret("pk-lf-wire"),
      Secret("sk-lf-wire"),
      "staging",
      "2.0.0",
      "2.1.0",
      Llm4sHttpClient.create()
    )

  private val run = TraceEvent.AgentRunEnded(
    "thread-9",
    "run-9",
    "assistant",
    TraceEvent.RunStatus.Completed,
    Seq(UserMessage("hi"), AssistantMessage("hello")),
    UsageSummary(),
    userId = Some("user-42")
  )

  "LangfuseTracing" should "POST one OTLP/JSON export per event to /api/public/otel/v1/traces" in {
    received.synchronized(received.clear())
    status = 200
    reply = "{}"

    tracing(s"$base/api/public/ingestion").traceEvent(run) shouldBe Right(())

    val request = received.synchronized(received.toList) match {
      case only :: Nil => only
      case other       => fail(s"expected one request, got ${other.size}")
    }
    request.method shouldBe "POST"
    request.path shouldBe "/api/public/otel/v1/traces"
    request.headers("authorization") shouldBe
      "Basic " + Base64.getEncoder.encodeToString("pk-lf-wire:sk-lf-wire".getBytes(StandardCharsets.UTF_8))
    request.headers("content-type") shouldBe "application/json"
    request.headers("x-langfuse-ingestion-version") shouldBe "4"

    val spans = SentSpan.all(request.body)
    spans.map(_.name) shouldBe Seq("LLM4S Agent Run", "User Input 0", "LLM Generation 1")
    val root = spans.head
    root.str("user.id") shouldBe "user-42"
    root.str("session.id") shouldBe "thread-9"
    root.str("langfuse.environment") shouldBe "staging"
    root.str("langfuse.release") shouldBe "2.0.0"
    root.str("langfuse.version") shouldBe "2.1.0"
    root.input.str shouldBe "hi"
    root.output.str shouldBe "hello"
  }

  it should "return a Left, and send once, when Langfuse answers 400" in {
    received.synchronized(received.clear())
    status = 400
    reply = """{"message":"Invalid request"}"""

    val result = tracing(base).traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))

    result.left.toOption.map(_.message).getOrElse(fail("expected Left")) should include("400")
    received.synchronized(received.size) shouldBe 1
  }
}
