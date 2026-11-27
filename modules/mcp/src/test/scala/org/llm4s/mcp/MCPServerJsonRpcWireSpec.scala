package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.toolapi.{ SafeParameterExtractor, Schema, ToolBuilder }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.util.concurrent.{ CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * The raw JSON-RPC 2.0 text [[MCPServer]] writes (#3071), read as JSON rather than through llm4s's own client,
 * which tolerates a missing `jsonrpc`, a string id for a numeric one and an empty `capabilities`. Every reply
 * carries `"jsonrpc":"2.0"` and the request's id with its own type; a reply to a message whose id cannot be
 * read has `"id":null`; `initialize` declares the tools capability; a notification or a client's response is
 * accepted with 202 and no body.
 */
class MCPServerJsonRpcWireSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  private var server: MCPServer = _
  private var port: Int         = _
  private val http              = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeAll(): Unit = {
    LoggerFactory.getLogger(classOf[MCPServer]).asInstanceOf[LBLogger].setLevel(Level.OFF)
    server = new MCPServer(MCPServerOptions(0, "/mcp", "WireServer", "1.0"), Seq(echoTool()))
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit = if (server != null) server.stop()

  private val initParams = ujson.Obj(
    "protocolVersion" -> "2025-06-18",
    "capabilities"    -> ujson.Obj(),
    "clientInfo"      -> ujson.Obj("name" -> "wire", "version" -> "1")
  )

  private def request(id: ujson.Value, method: String, params: ujson.Value = ujson.Obj()): String =
    ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))

  private def post(body: String, headers: (String, String)*): HttpResponse[String] = {
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port/mcp"))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
    headers.foreach { case (k, v) => builder.header(k, v) }
    http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
  }

  private def session(): String = {
    val reply = post(request(ujson.Str("init"), "initialize", initParams))
    reply.statusCode() shouldBe 200
    reply.headers().firstValue("mcp-session-id").orElseThrow()
  }

  private def postInSession(body: String): HttpResponse[String] =
    post(body, "mcp-session-id" -> session(), "MCP-Protocol-Version" -> "2025-06-18")

  /** The reply's members, after checking it is an object carrying exactly one of `result` and `error`. */
  private def envelope(text: String): collection.Map[String, ujson.Value] = {
    val fields = ujson.read(text).obj
    fields.get("jsonrpc") shouldBe Some(ujson.Str("2.0"))
    fields.contains("id") shouldBe true
    (fields.contains("result") ^ fields.contains("error")) shouldBe true
    fields
  }

  describe("Streamable HTTP replies") {
    it("answer initialize with jsonrpc, the numeric id as a number and the tools capability") {
      val reply  = post(request(ujson.Num(1), "initialize", initParams))
      val fields = envelope(reply.body())
      fields("id") shouldBe ujson.Num(1)
      fields("result")("capabilities") shouldBe ujson.Obj("tools" -> ujson.Obj("listChanged" -> false))
      reply.body() should include(""""id":1,""")
    }

    it("keep a string id a string, even one that looks like a number") {
      envelope(post(request(ujson.Str("1"), "initialize", initParams)).body())("id") shouldBe ujson.Str("1")
    }

    it("keep a fractional and a negative numeric id") {
      envelope(post(request(ujson.Num(-3), "initialize", initParams)).body())("id") shouldBe ujson.Num(-3)
      envelope(post(request(ujson.Num(2.5), "initialize", initParams)).body())("id") shouldBe ujson.Num(2.5)
    }

    it("answer tools/list and tools/call with the numeric id") {
      val list = envelope(postInSession(request(ujson.Num(7), "tools/list")).body())
      list("id") shouldBe ujson.Num(7)
      list("result")("tools").arr.map(_("name").str) shouldBe Seq("echo")

      val call = envelope(
        postInSession(
          request(ujson.Num(8), "tools/call", ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj("m" -> "hi")))
        ).body()
      )
      call("id") shouldBe ujson.Num(8)
      call("result")("content")(0)("text").str shouldBe "hi"
    }

    it("answer an unknown method, an unknown tool and an unknown session with the numeric id") {
      val unknownMethod = envelope(postInSession(request(ujson.Num(9), "nope/nope")).body())
      unknownMethod("id") shouldBe ujson.Num(9)
      unknownMethod("error")("code").num shouldBe MCPErrorCodes.METHOD_NOT_FOUND

      val unknownTool = envelope(postInSession(request(ujson.Num(10), "tools/call", ujson.Obj("name" -> "zzz"))).body())
      unknownTool("id") shouldBe ujson.Num(10)
      unknownTool("error")("code").num shouldBe MCPErrorCodes.TOOL_NOT_FOUND

      val badSession = envelope(
        post(request(ujson.Num(11), "tools/list"), "mcp-session-id" -> "nope", "MCP-Protocol-Version" -> "2025-06-18")
          .body()
      )
      badSession("id") shouldBe ujson.Num(11)
    }

    it("answer an unsupported protocol version with the numeric id") {
      val reply = envelope(post(request(ujson.Num(12), "tools/list"), "MCP-Protocol-Version" -> "1999-01-01").body())
      reply("id") shouldBe ujson.Num(12)
      reply("error")("code").num shouldBe MCPErrorCodes.INVALID_PROTOCOL_VERSION
    }

    it("answer malformed JSON with a parse error whose id is null") {
      val reply  = post("not json")
      val fields = envelope(reply.body())
      reply.statusCode() shouldBe 400
      fields("id") shouldBe ujson.Null
      fields("error")("code").num shouldBe MCPErrorCodes.PARSE_ERROR
    }

    it("answer a request object that is not valid JSON-RPC with Invalid Request and the id it carries") {
      val reply  = post("""{"id":"x","nope":1}""")
      val fields = envelope(reply.body())
      reply.statusCode() shouldBe 400
      fields("id") shouldBe ujson.Str("x")
      fields("error")("code").num shouldBe MCPErrorCodes.INVALID_REQUEST

      val noVersion = envelope(post("""{"id":4,"method":"tools/list"}""").body())
      noVersion("id") shouldBe ujson.Num(4)
      noVersion("error")("code").num shouldBe MCPErrorCodes.INVALID_REQUEST
    }

    it("answer a null, boolean or object id with Invalid Request and a null id") {
      Seq("null", "true", """{"a":1}""").foreach { id =>
        val fields = envelope(post(s"""{"jsonrpc":"2.0","id":$id,"method":"tools/list"}""").body())
        fields("id") shouldBe ujson.Null
        fields("error")("code").num shouldBe MCPErrorCodes.INVALID_REQUEST
      }
    }

    it("answer a batch, which MCP 2025-06-18 does not support, with one Invalid Request whose id is null") {
      val reply  = post(s"[${request(ujson.Num(1), "initialize", initParams)}]")
      val fields = envelope(reply.body())
      reply.statusCode() shouldBe 400
      fields("id") shouldBe ujson.Null
      fields("error")("code").num shouldBe MCPErrorCodes.INVALID_REQUEST
    }

    it("accept a notification and a client's response with 202 and no body") {
      val notification = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      notification.statusCode() shouldBe 202
      notification.body() shouldBe ""

      val response = post("""{"jsonrpc":"2.0","id":5,"result":{}}""")
      response.statusCode() shouldBe 202
      response.body() shouldBe ""
    }
  }

  describe("HTTP+SSE (2024-11-05) replies") {
    it("carry jsonrpc, the numeric id and the tools capability") {
      withSSESession { (messageUrl, events) =>
        postTo(messageUrl, request(ujson.Num(1), "initialize", initParams)) shouldBe 202
        val init = envelope(nextEvent(events))
        init("id") shouldBe ujson.Num(1)
        init("result")("capabilities") shouldBe ujson.Obj("tools" -> ujson.Obj("listChanged" -> false))

        postTo(messageUrl, request(ujson.Num(2), "tools/call", ujson.Obj("name" -> "zzz"))) shouldBe 202
        val unknownTool = envelope(nextEvent(events))
        unknownTool("id") shouldBe ujson.Num(2)
        unknownTool("error")("code").num shouldBe MCPErrorCodes.TOOL_NOT_FOUND

        postTo(messageUrl, request(ujson.Str("3"), "tools/list")) shouldBe 202
        envelope(nextEvent(events))("id") shouldBe ujson.Str("3")
      }
    }

    it("accept a client's response with 202 and send nothing on the stream") {
      withSSESession { (messageUrl, events) =>
        postTo(messageUrl, """{"jsonrpc":"2.0","id":5,"result":{}}""") shouldBe 202
        events.poll(300, TimeUnit.MILLISECONDS) shouldBe null
      }
    }
  }

  private def nextEvent(events: LinkedBlockingQueue[String]): String = {
    val event = events.poll(5, TimeUnit.SECONDS)
    event should not be null
    event.linesIterator.find(_.startsWith("data: ")).map(_.stripPrefix("data: ")).getOrElse("")
  }

  private def postTo(url: String, body: String): Int =
    http
      .send(
        HttpRequest
          .newBuilder(URI.create(url))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.discarding()
      )
      .statusCode()

  private def withSSESession(body: (String, LinkedBlockingQueue[String]) => Unit): Unit = {
    val events     = new LinkedBlockingQueue[String]()
    val endpoint   = new CountDownLatch(1)
    val messageUrl = new AtomicReference[String]("")
    val exec       = Executors.newSingleThreadExecutor()
    val stream     = SseTestStream.open(port, "/mcp/sse")
    exec.submit(new Runnable {
      override def run(): Unit = {
        val _ = Try {
          val reader = new BufferedReader(new InputStreamReader(stream.body, "UTF-8"))
          val sb     = new StringBuilder
          Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
            if (line.isEmpty) {
              val event = sb.toString
              if (event.startsWith("event: endpoint")) {
                messageUrl
                  .set(event.linesIterator.find(_.startsWith("data: ")).map(_.stripPrefix("data: ")).getOrElse(""))
                endpoint.countDown()
              } else if (event.startsWith("event: message")) events.put(event)
              sb.clear()
            } else {
              if (sb.nonEmpty) sb.append("\n")
              sb.append(line)
            }
          }
        }
      }
    })
    try {
      endpoint.await(5, TimeUnit.SECONDS) shouldBe true
      body(messageUrl.get(), events)
    } finally {
      stream.close()
      exec.shutdown()
      exec.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  private def echoTool() = {
    val schema = Schema.`object`[Map[String, Any]]("Params").withProperty(Schema.property("m", Schema.string("Msg")))
    ToolBuilder[String]("echo", "Echoes m", schema)
      .withHandler((p: SafeParameterExtractor) => p.getString("m"))
      .build()
      .fold(e => throw new RuntimeException(e.formatted), identity)
  }
}
