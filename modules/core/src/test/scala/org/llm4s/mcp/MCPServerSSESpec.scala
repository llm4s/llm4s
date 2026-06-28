package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.llm4s.toolapi.{ Schema, SafeParameterExtractor, ToolBuilder }
import org.slf4j.LoggerFactory
import upickle.default.{ read => upickleRead, write => upickleWrite }

import java.io.{ BufferedReader, InputStreamReader }
import java.net.{ HttpURLConnection, URI }
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for the MCPServer SSE transport (MCP 2024-11-05 protocol).
 *
 * The 2024-11-05 SSE protocol works as follows:
 *   1. Client GETs `{path}/sse` → server opens a persistent SSE stream.
 *   2. First SSE event is `event: endpoint\ndata: <POST_URL>`.
 *   3. Client POSTs JSON-RPC requests to `<POST_URL>?sessionId=<id>`.
 *   4. Server delivers JSON-RPC responses as `event: message` SSE events.
 *
 * These tests verify the full round-trip from connection establishment through
 * tool execution.
 */
class MCPServerSSESpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  private var server: MCPServer = _
  private var port: Int         = _

  override def beforeAll(): Unit = {
    silenceLogs()

    val pingTool = buildPingTool()
    val opts     = MCPServerOptions(0, "/mcp", "SSETestServer", "1.0-test")
    server = new MCPServer(opts, Seq(pingTool))
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit =
    if (server != null) server.stop()

  describe("SSE GET /sse") {

    it("should return 200 with Content-Type text/event-stream") {
      val conn = openConnection(port, "/mcp/sse", "GET")
      conn.setRequestProperty("Accept", "text/event-stream")
      conn.setConnectTimeout(3000)
      conn.setReadTimeout(3000)

      conn.getResponseCode shouldBe 200
      conn.getHeaderField("Content-Type") should include("text/event-stream")
    }

    it("should immediately send an endpoint SSE event") {
      val endpointEvent = readFirstSSEEvent(port, "/mcp/sse")
      endpointEvent should include("event: endpoint")
      endpointEvent should include("data: http://")
      endpointEvent should include("/mcp/messages?sessionId=")
    }

    it("should embed a unique sessionId in the endpoint URL") {
      val event1 = readFirstSSEEvent(port, "/mcp/sse")
      val event2 = readFirstSSEEvent(port, "/mcp/sse")
      val url1   = extractMessageUrl(event1)
      val url2   = extractMessageUrl(event2)
      (url1 should not).equal(url2)
    }
  }

  describe("SSE POST /mcp/messages") {

    it("should return 400 when sessionId query parameter is missing") {
      val conn = openConnection(port, "/mcp/messages", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.getOutputStream.write("{}".getBytes("UTF-8"))
      conn.getResponseCode shouldBe 400
    }

    it("should return 400 for an unknown sessionId") {
      val conn = openConnection(port, "/mcp/messages?sessionId=nonexistent-session", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.getOutputStream.write("{}".getBytes("UTF-8"))
      conn.getResponseCode shouldBe 400
    }
  }

  describe("SSE full round-trip") {

    it("should handle an initialize request and return a 2024-11-05 response") {
      withSSESession(port) { (messageUrl, events) =>
        val initReq = mkRequest(
          "init-1",
          "initialize",
          Some(
            ujson.Obj(
              "protocolVersion" -> "2024-11-05",
              "capabilities"    -> ujson.Obj(),
              "clientInfo"      -> ujson.Obj("name" -> "test-client", "version" -> "1.0")
            )
          )
        )
        val postCode = postJson(messageUrl, initReq)
        postCode shouldBe 202

        val responseEvent = events.poll(5, TimeUnit.SECONDS)
        responseEvent should not be null

        val response = upickleRead[JsonRpcResponse](extractEventData(responseEvent))
        response.id shouldBe "init-1"
        response.error shouldBe None
        val result = response.result.get
        result("protocolVersion").str shouldBe "2024-11-05"
        result("serverInfo")("name").str shouldBe "SSETestServer"
      }
    }

    it("should list tools over SSE") {
      withSSESession(port) { (messageUrl, events) =>
        val req      = mkRequest("list-1", "tools/list", None)
        val postCode = postJson(messageUrl, req)
        postCode shouldBe 202

        val responseEvent = events.poll(5, TimeUnit.SECONDS)
        responseEvent should not be null

        val response = upickleRead[JsonRpcResponse](extractEventData(responseEvent))
        response.id shouldBe "list-1"
        response.error shouldBe None
        val toolsArr = response.result.get("tools").arr
        toolsArr should have size 1
        toolsArr.head("name").str shouldBe "ping"
      }
    }

    it("should execute a tool call over SSE") {
      withSSESession(port) { (messageUrl, events) =>
        val req = mkRequest(
          "call-1",
          "tools/call",
          Some(ujson.Obj("name" -> "ping", "arguments" -> ujson.Obj("message" -> "SSE hello")))
        )
        val postCode = postJson(messageUrl, req)
        postCode shouldBe 202

        val responseEvent = events.poll(5, TimeUnit.SECONDS)
        responseEvent should not be null

        val response = upickleRead[JsonRpcResponse](extractEventData(responseEvent))
        response.id shouldBe "call-1"
        response.error shouldBe None
        val content = response.result.get("content").arr
        content.head("text").str should include("Echo: SSE hello")
      }
    }

    it("should return a METHOD_NOT_FOUND error for unknown methods") {
      withSSESession(port) { (messageUrl, events) =>
        val req      = mkRequest("err-1", "unknown/method", None)
        val postCode = postJson(messageUrl, req)
        postCode shouldBe 202

        val responseEvent = events.poll(5, TimeUnit.SECONDS)
        responseEvent should not be null

        val response = upickleRead[JsonRpcResponse](extractEventData(responseEvent))
        response.id shouldBe "err-1"
        response.error.map(_.code) shouldBe Some(MCPErrorCodes.METHOD_NOT_FOUND)
      }
    }

    it("should handle multiple sequential requests on the same SSE session") {
      withSSESession(port) { (messageUrl, events) =>
        Seq("msg-1", "msg-2", "msg-3").foreach { id =>
          val req =
            mkRequest(id, "tools/call", Some(ujson.Obj("name" -> "ping", "arguments" -> ujson.Obj("message" -> id))))
          postJson(messageUrl, req) shouldBe 202
        }

        val responses = (1 to 3).map { _ =>
          val ev = events.poll(5, TimeUnit.SECONDS)
          ev should not be null
          upickleRead[JsonRpcResponse](extractEventData(ev))
        }

        val ids = responses.map(_.id).toSet
        ids shouldBe Set("msg-1", "msg-2", "msg-3")
        responses.foreach(r => r.error shouldBe None)
      }
    }

    it("should acknowledge a JSON-RPC notification (no id) with 202 and no SSE event") {
      withSSESession(port) { (messageUrl, events) =>
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}"""
        val code         = postJson(messageUrl, notification)
        code shouldBe 202
        val event = events.poll(1, TimeUnit.SECONDS)
        event shouldBe null
      }
    }

    it("should return 400 for a POST to messages with an invalid JSON body") {
      withSSESession(port) { (messageUrl, _) =>
        val code = postJson(messageUrl, "not-valid-json")
        code shouldBe 400
      }
    }

    it("should return 400 for a POST to messages with valid JSON but un-parseable as JsonRpcRequest") {
      withSSESession(port) { (messageUrl, _) =>
        val code = postJson(messageUrl, """{"id":"x","unexpected":true}""")
        code shouldBe 400
      }
    }

    it("should handle SSE initialize request without params using default InitializeRequest") {
      withSSESession(port) { (messageUrl, events) =>
        val req      = mkRequest("init-default", "initialize", None)
        val postCode = postJson(messageUrl, req)
        postCode shouldBe 202

        val responseEvent = events.poll(5, TimeUnit.SECONDS)
        responseEvent should not be null

        val response = upickleRead[JsonRpcResponse](extractEventData(responseEvent))
        response.id shouldBe "init-default"
        response.error shouldBe None
        response.result.get("protocolVersion").str shouldBe "2024-11-05"
      }
    }
  }

  private def withSSESession(
    serverPort: Int
  )(body: (String, java.util.concurrent.LinkedBlockingQueue[String]) => Unit): Unit = {
    val events        = new java.util.concurrent.LinkedBlockingQueue[String]()
    val endpointLatch = new CountDownLatch(1)
    val messageUrl    = new AtomicReference[String]("")
    val executor      = Executors.newSingleThreadExecutor()

    val conn = openConnection(serverPort, "/mcp/sse", "GET")
    conn.setRequestProperty("Accept", "text/event-stream")
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(10000)

    executor.submit(new Runnable {
      override def run(): Unit =
        try {
          val reader = new BufferedReader(new InputStreamReader(conn.getInputStream, "UTF-8"))
          val sb     = new StringBuilder

          var line = reader.readLine()
          while (line != null) {
            if (line.isEmpty) {
              val event = sb.toString()
              if (event.startsWith("event: endpoint")) {
                messageUrl.set(extractMessageUrl(event))
                endpointLatch.countDown()
              } else if (event.startsWith("event: message")) {
                events.put(event)
              }
              sb.clear()
            } else {
              if (sb.nonEmpty) sb.append("\n")
              sb.append(line)
            }
            line = reader.readLine()
          }
        } catch {
          case _: Exception => ()
        }
    })

    try {
      val gotEndpoint = endpointLatch.await(5, TimeUnit.SECONDS)
      gotEndpoint shouldBe true
      body(messageUrl.get(), events)
    } finally {
      conn.disconnect()
      executor.shutdown()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  private def readFirstSSEEvent(serverPort: Int, path: String): String = {
    val conn = openConnection(serverPort, path, "GET")
    conn.setRequestProperty("Accept", "text/event-stream")
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(3000)

    try {
      val reader = new BufferedReader(new InputStreamReader(conn.getInputStream, "UTF-8"))
      val sb     = new StringBuilder

      var line = reader.readLine()
      while (line != null && line.nonEmpty) {
        if (sb.nonEmpty) sb.append("\n")
        sb.append(line)
        line = reader.readLine()
      }
      sb.toString()
    } finally conn.disconnect()
  }

  private def postJson(url: String, body: String): Int = {
    val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(3000)
    conn.getOutputStream.write(body.getBytes("UTF-8"))
    val code = conn.getResponseCode
    conn.disconnect()
    code
  }

  private def openConnection(serverPort: Int, path: String, method: String): HttpURLConnection = {
    val conn = URI.create(s"http://127.0.0.1:$serverPort$path").toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod(method)
    conn
  }

  private def extractMessageUrl(sseEvent: String): String =
    sseEvent.linesIterator
      .find(_.startsWith("data: "))
      .map(_.stripPrefix("data: ").trim)
      .getOrElse("")

  private def extractEventData(sseEvent: String): String =
    sseEvent.linesIterator
      .find(_.startsWith("data: "))
      .map(_.stripPrefix("data: ").trim)
      .getOrElse("{}")

  private def mkRequest(id: String, method: String, params: Option[ujson.Value]): String =
    upickleWrite(JsonRpcRequest(jsonrpc = "2.0", id = id, method = method, params = params))

  private def buildPingTool() = {
    val schema = Schema
      .`object`[Map[String, Any]]("Ping parameters")
      .withProperty(Schema.property("message", Schema.string("Message to echo")))

    ToolBuilder[Map[String, Any], String]("ping", "Echoes a message", schema)
      .withHandler((params: SafeParameterExtractor) => params.getString("message").map(m => s"Echo: $m"))
      .buildSafe()
      .fold(e => throw new RuntimeException(s"Tool build failed: ${e.formatted}"), identity)
  }

  private def silenceLogs(): Unit = {
    val logger = LoggerFactory.getLogger("org.llm4s.mcp.MCPServer").asInstanceOf[LBLogger]
    logger.setLevel(Level.OFF)
  }
}
