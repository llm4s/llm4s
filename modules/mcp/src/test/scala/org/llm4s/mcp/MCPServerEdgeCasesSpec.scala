package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.toolapi.{ SafeParameterExtractor, Schema, ToolBuilder }
import org.slf4j.LoggerFactory
import upickle.default.{ read => upickleRead }

import java.io.{ BufferedReader, InputStreamReader }
import java.net.{ HttpURLConnection, URI }
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers edge-case paths in MCPServer not exercised by the main integration specs:
 *  - double-start, DELETE 400/404, 405 method not allowed
 *  - POST payload limit, bad JSON, malformed request, notification failure
 *  - invalid session, unknown method, protocol-version negotiation
 *  - tool-not-found, tool-execution-failure, non-String tool result
 *  - SSE notification, SSE bad-JSON, SSE invalid JSON-RPC, SSE invalid init params
 *  - SSE endpoint URL when server binds to 0.0.0.0
 */
class MCPServerEdgeCasesSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  private var server: MCPServer = _
  private var port: Int         = _

  override def beforeAll(): Unit = {
    silenceLogs()
    val opts = MCPServerOptions(0, "/mcp", "EdgeServer", "1.0")
    server = new MCPServer(opts, Seq(buildPingTool(), buildFailTool(), buildIntTool()))
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit =
    if (server != null) server.stop()

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  describe("MCPServer lifecycle") {
    it("start() on an already-running server should return Right(())") {
      server.start() shouldBe Right(())
    }
  }

  // ── DELETE endpoint ────────────────────────────────────────────────────────

  describe("DELETE endpoint") {
    it("should return 400 when mcp-session-id header is absent") {
      mkConn("/mcp", "DELETE").getResponseCode shouldBe 400
    }

    it("should return 404 when session ID does not exist") {
      val c = mkConn("/mcp", "DELETE")
      c.setRequestProperty("mcp-session-id", "no-such-session")
      c.getResponseCode shouldBe 404
    }

    it("should return 200 and hit the removeSession(existing) branch") {
      val (sid, _) = initSession()
      val c        = mkConn("/mcp", "DELETE")
      c.setRequestProperty("mcp-session-id", sid)
      c.getResponseCode shouldBe 200
    }
  }

  // ── HTTP method routing ────────────────────────────────────────────────────

  describe("HTTP method routing") {
    it("should return 405 for unsupported HTTP method PUT") {
      mkConn("/mcp", "PUT").getResponseCode shouldBe 405
    }
  }

  // ── POST payload handling ─────────────────────────────────────────────────

  describe("POST - payload handling") {
    it("should return PARSE_ERROR for completely invalid JSON body") {
      val c = postConn()
      c.getOutputStream.write("not-valid-json".getBytes("UTF-8"))
      c.getResponseCode shouldBe 200
      readBody(c) should include("Parse error")
    }

    it("should return PARSE_ERROR when JSON has id but cannot be parsed as JsonRpcRequest") {
      val c = postConn()
      c.getOutputStream.write("""{"id":"x","nope":1}""".getBytes("UTF-8"))
      c.getResponseCode shouldBe 200
      readBody(c) should include("Parse error")
    }

    it("should return 200 for a valid JSON-RPC notification (no id)") {
      val c = postConn()
      c.getOutputStream.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""".getBytes("UTF-8"))
      c.getResponseCode shouldBe 200
    }

    it("should return 200 for a malformed notification (no id, no method) hitting failure path") {
      val c = postConn()
      c.getOutputStream.write("""{"foo":"bar"}""".getBytes("UTF-8"))
      c.getResponseCode shouldBe 200
    }
  }

  // ── POST method routing ───────────────────────────────────────────────────

  describe("POST - method routing") {
    it("should return METHOD_NOT_FOUND for unknown JSON-RPC method with valid session") {
      val (sid, _) = initSession()
      val c        = sessionConn(sid)
      c.getOutputStream.write(rpc("u-1", "unknown/method", None).getBytes("UTF-8"))
      c.getResponseCode shouldBe 200
      readBody(c) should include("Method not found")
    }

    it("should return Invalid session error for unrecognised session ID") {
      val c = mkConn("/mcp", "POST")
      c.setDoOutput(true)
      c.setRequestProperty("Content-Type", "application/json")
      c.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
      c.setRequestProperty("mcp-session-id", "bad-session-id")
      c.getOutputStream.write(rpc("s-1", "tools/list", None).getBytes("UTF-8"))
      c.getResponseCode shouldBe 200
      readBody(c) should include("Invalid session")
    }
  }

  // ── POST initialize ────────────────────────────────────────────────────────

  describe("POST - initialize") {
    it("should fall back to 2025-06-18 when client sends unsupported protocol version") {
      val c = postConn()
      c.getOutputStream.write(
        rpc(
          "i-1",
          "initialize",
          Some(
            ujson.Obj(
              "protocolVersion" -> "0.0.1",
              "capabilities"    -> ujson.Obj(),
              "clientInfo"      -> ujson.Obj("name" -> "test", "version" -> "1.0")
            )
          )
        ).getBytes("UTF-8")
      )
      c.getResponseCode shouldBe 200
      readBody(c) should include("2025-06-18")
    }

    it("should use default InitializeRequest when params cannot be parsed") {
      val c = postConn()
      c.getOutputStream.write(
        rpc("i-2", "initialize", Some(ujson.Obj("bad" -> "params"))).getBytes("UTF-8")
      )
      c.getResponseCode shouldBe 200
      readBody(c) should include("2025-06-18")
    }
  }

  // ── POST tool calls ────────────────────────────────────────────────────────

  describe("POST - tool calls") {
    it("should return TOOL_NOT_FOUND for unknown tool name") {
      val (sid, _) = initSession()
      val c        = sessionConn(sid)
      c.getOutputStream.write(
        rpc("t-1", "tools/call", Some(ujson.Obj("name" -> "no_such_tool", "arguments" -> ujson.Obj())))
          .getBytes("UTF-8")
      )
      c.getResponseCode shouldBe 200
      readBody(c) should include("Tool not found")
    }

    it("should return TOOL_EXECUTION_ERROR when tool handler returns Left") {
      val (sid, _) = initSession()
      val c        = sessionConn(sid)
      c.getOutputStream.write(
        rpc("t-2", "tools/call", Some(ujson.Obj("name" -> "fail_tool", "arguments" -> ujson.Obj("input" -> "x"))))
          .getBytes("UTF-8")
      )
      c.getResponseCode shouldBe 200
      readBody(c) should include("Tool failed")
    }

    it("should call render() for non-String tool results (Int -> ujson.Num)") {
      val (sid, _) = initSession()
      val c        = sessionConn(sid)
      c.getOutputStream.write(
        rpc("t-3", "tools/call", Some(ujson.Obj("name" -> "int_tool", "arguments" -> ujson.Obj())))
          .getBytes("UTF-8")
      )
      c.getResponseCode shouldBe 200
      readBody(c) should include("42")
    }

    it("should use empty-object default for missing arguments field") {
      val (sid, _) = initSession()
      val c        = sessionConn(sid)
      // Send tools/call with no "arguments" key – hits getOrElse(ujson.Obj()) branch
      c.getOutputStream.write(
        rpc("t-4", "tools/call", Some(ujson.Obj("name" -> "int_tool"))).getBytes("UTF-8")
      )
      c.getResponseCode shouldBe 200
      readBody(c) should include("42")
    }
  }

  // ── SSE edge cases ────────────────────────────────────────────────────────

  describe("SSE transport - edge cases") {
    it("should use 127.0.0.1 in endpoint URL when server is bound to 0.0.0.0") {
      // Binding 0.0.0.0 requires an apiKey (start() refuses a public bind otherwise).
      val key = "wildcard-key"
      val s = new MCPServer(
        MCPServerOptions(0, "/mcp", "WildcardServer", "1.0", apiKey = Some(key), host = "0.0.0.0"),
        Seq(buildPingTool())
      )
      s.start().fold(e => throw e, _ => ())
      try {
        val event = readFirstSSEEvent(s.boundPort, "/mcp/sse", Some(key))
        extractMessageUrl(event) should startWith("http://127.0.0.1:")
      } finally s.stop()
    }

    it("should refuse to start when bound to a non-loopback host without an apiKey") {
      val s = new MCPServer(
        MCPServerOptions(0, "/mcp", "InsecureServer", "1.0", host = "0.0.0.0"),
        Seq(buildPingTool())
      )
      val result = s.start()
      result.isLeft shouldBe true
      result.left.toOption.map(_.getMessage).getOrElse("") should include("not loopback")
    }

    it("should return 202 for a valid SSE notification (no id)") {
      withSSESession { (msgUrl, _) =>
        val c = postTo(msgUrl)
        c.getOutputStream.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""".getBytes("UTF-8"))
        c.getResponseCode shouldBe 202
      }
    }

    it("should return 400 for SSE POST with bad JSON body") {
      withSSESession { (msgUrl, _) =>
        val c = postTo(msgUrl)
        c.getOutputStream.write("not-json".getBytes("UTF-8"))
        c.getResponseCode shouldBe 400
      }
    }

    it("should return 400 for SSE POST with id but structurally invalid JSON-RPC") {
      withSSESession { (msgUrl, _) =>
        val c = postTo(msgUrl)
        c.getOutputStream.write("""{"id":"x","bad":true}""".getBytes("UTF-8"))
        c.getResponseCode shouldBe 400
      }
    }

    it("should use default InitializeRequest in SSE when params cannot be parsed") {
      withSSESession { (msgUrl, events) =>
        val c = postTo(msgUrl)
        c.getOutputStream.write(
          rpc("si-1", "initialize", Some(ujson.Obj("broken" -> "params"))).getBytes("UTF-8")
        )
        c.getResponseCode shouldBe 202
        val ev = events.poll(5, TimeUnit.SECONDS)
        ev should not be null
        val resp = upickleRead[JsonRpcResponse](extractEventData(ev))
        resp.id shouldBe "si-1"
        resp.result.get("protocolVersion").str shouldBe "2024-11-05"
      }
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def mkConn(path: String, method: String): HttpURLConnection = {
    val c = URI.create(s"http://127.0.0.1:$port$path").toURL.openConnection().asInstanceOf[HttpURLConnection]
    c.setRequestMethod(method)
    c.setConnectTimeout(3000)
    c.setReadTimeout(3000)
    c
  }

  private def postConn(): HttpURLConnection = {
    val c = mkConn("/mcp", "POST")
    c.setDoOutput(true)
    c.setRequestProperty("Content-Type", "application/json")
    c
  }

  private def sessionConn(sessionId: String): HttpURLConnection = {
    val c = postConn()
    c.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
    c.setRequestProperty("mcp-session-id", sessionId)
    c
  }

  private def postTo(url: String): HttpURLConnection = {
    val c = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    c.setRequestMethod("POST")
    c.setDoOutput(true)
    c.setRequestProperty("Content-Type", "application/json")
    c.setConnectTimeout(3000)
    c.setReadTimeout(3000)
    c
  }

  private def initSession(): (String, String) = {
    val c = postConn()
    c.getOutputStream.write(
      rpc(
        "init-ec",
        "initialize",
        Some(
          ujson.Obj(
            "protocolVersion" -> "2025-06-18",
            "capabilities"    -> ujson.Obj(),
            "clientInfo"      -> ujson.Obj("name" -> "test", "version" -> "1.0")
          )
        )
      ).getBytes("UTF-8")
    )
    c.getResponseCode shouldBe 200
    (c.getHeaderField("mcp-session-id"), readBody(c))
  }

  private def readBody(c: HttpURLConnection): String = {
    val s = if (c.getResponseCode < 400) c.getInputStream else c.getErrorStream
    if (s == null) "" else scala.io.Source.fromInputStream(s).mkString
  }

  private def rpc(id: String, method: String, params: Option[ujson.Value]): String = {
    val o = ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method)
    params.foreach(p => o("params") = p)
    ujson.write(o)
  }

  private def withSSESession(
    body: (String, java.util.concurrent.LinkedBlockingQueue[String]) => Unit
  ): Unit = {
    val events = new java.util.concurrent.LinkedBlockingQueue[String]()
    val latch  = new CountDownLatch(1)
    val msgUrl = new AtomicReference[String]("")
    val exec   = Executors.newSingleThreadExecutor()

    val sseConn = mkConn("/mcp/sse", "GET")
    sseConn.setRequestProperty("Accept", "text/event-stream")
    sseConn.setReadTimeout(10000)

    exec.submit(new Runnable {
      override def run(): Unit =
        try {
          val reader = new BufferedReader(new InputStreamReader(sseConn.getInputStream, "UTF-8"))
          val sb     = new StringBuilder
          var line   = reader.readLine()
          while (line != null) {
            if (line.isEmpty) {
              val ev = sb.toString()
              if (ev.startsWith("event: endpoint")) {
                msgUrl.set(extractMessageUrl(ev))
                latch.countDown()
              } else if (ev.startsWith("event: message")) {
                events.put(ev)
              }
              sb.clear()
            } else {
              if (sb.nonEmpty) sb.append("\n")
              sb.append(line)
            }
            line = reader.readLine()
          }
        } catch {
          case _: Exception => // closed by test
        }
    })

    try {
      latch.await(5, TimeUnit.SECONDS) shouldBe true
      body(msgUrl.get(), events)
    } finally {
      sseConn.disconnect()
      exec.shutdown()
      exec.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  private def readFirstSSEEvent(serverPort: Int, path: String, apiKey: Option[String]): String = {
    val c =
      URI.create(s"http://127.0.0.1:$serverPort$path").toURL.openConnection().asInstanceOf[HttpURLConnection]
    c.setRequestMethod("GET")
    c.setConnectTimeout(3000)
    c.setReadTimeout(3000)
    c.setRequestProperty("Accept", "text/event-stream")
    apiKey.foreach(k => c.setRequestProperty("Authorization", s"Bearer $k"))
    try {
      val reader = new BufferedReader(new InputStreamReader(c.getInputStream, "UTF-8"))
      val sb     = new StringBuilder
      var line   = reader.readLine()
      while (line != null && line.nonEmpty) {
        if (sb.nonEmpty) sb.append("\n")
        sb.append(line)
        line = reader.readLine()
      }
      sb.toString()
    } finally c.disconnect()
  }

  private def extractMessageUrl(ev: String): String =
    ev.linesIterator.find(_.startsWith("data: ")).map(_.stripPrefix("data: ").trim).getOrElse("")

  private def extractEventData(ev: String): String =
    ev.linesIterator.find(_.startsWith("data: ")).map(_.stripPrefix("data: ").trim).getOrElse("{}")

  private def buildPingTool() = {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("message", Schema.string("Msg")))
    ToolBuilder[Map[String, Any], String]("ping", "Echoes", schema)
      .withHandler((p: SafeParameterExtractor) => p.getString("message").map(m => s"Echo: $m"))
      .buildSafe()
      .fold(e => throw new RuntimeException(e.formatted), identity)
  }

  private def buildFailTool() = {
    val schema = Schema
      .`object`[Map[String, Any]]("Params")
      .withProperty(Schema.property("input", Schema.string("Input")))
    ToolBuilder[Map[String, Any], String]("fail_tool", "Always fails", schema)
      .withHandler((_: SafeParameterExtractor) => Left("intentional failure"))
      .buildSafe()
      .fold(e => throw new RuntimeException(e.formatted), identity)
  }

  private def buildIntTool() = {
    val schema = Schema.`object`[Map[String, Any]]("Params")
    ToolBuilder[Map[String, Any], Int]("int_tool", "Returns integer 42", schema)
      .withHandler((_: SafeParameterExtractor) => Right(42))
      .buildSafe()
      .fold(e => throw new RuntimeException(e.formatted), identity)
  }

  private def silenceLogs(): Unit = {
    val logger = LoggerFactory.getLogger("org.llm4s.mcp.MCPServer").asInstanceOf[LBLogger]
    logger.setLevel(Level.OFF)
  }
}
