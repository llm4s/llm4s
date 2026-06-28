package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.llm4s.toolapi.{ Schema, SafeParameterExtractor, ToolBuilder }
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter }
import java.net.{ HttpURLConnection, Socket, URI }
import scala.concurrent.duration._

class MCPServerSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  var server: MCPServer                        = _
  var port: Int                                = _
  private var previousMcpServerLogLevel: Level = _

  override def beforeAll(): Unit = {
    val logger = LoggerFactory.getLogger("org.llm4s.mcp.MCPServer").asInstanceOf[LBLogger]
    previousMcpServerLogLevel = logger.getLevel
    logger.setLevel(Level.OFF)

    val pingSchema = Schema
      .`object`[Map[String, Any]]("Ping parameters")
      .withProperty(Schema.property("message", Schema.string("Message to echo back")))

    def pingHandler(params: SafeParameterExtractor): Either[String, String] =
      params.getString("message").map(msg => s"Echo: $msg")

    val failSchema = Schema.`object`[Map[String, Any]]("Fail parameters")

    def failHandler(params: SafeParameterExtractor): Either[String, String] = {
      val _ = params
      Left("intentional tool failure")
    }

    val intSchema = Schema
      .`object`[Map[String, Any]]("Int parameters")
      .withProperty(Schema.property("n", Schema.string("A number string")))

    def intHandler(params: SafeParameterExtractor): Either[String, Int] = {
      val _ = params
      Right(42)
    }

    val pingTool = ToolBuilder[Map[String, Any], String]("ping", "Echoes a message", pingSchema)
      .withHandler(pingHandler)
      .buildSafe()
      .fold(e => fail(s"ping tool failed: ${e.formatted}"), identity)

    val failTool = ToolBuilder[Map[String, Any], String]("fail_tool", "Always fails", failSchema)
      .withHandler(failHandler)
      .buildSafe()
      .fold(e => fail(s"fail tool failed: ${e.formatted}"), identity)

    val intTool = ToolBuilder[Map[String, Any], Int]("int_tool", "Returns integer", intSchema)
      .withHandler(intHandler)
      .buildSafe()
      .fold(e => fail(s"int tool failed: ${e.formatted}"), identity)

    val options = MCPServerOptions(0, "/mcp", "TestServer", "1.0.0")
    server = new MCPServer(options, Seq(pingTool, failTool, intTool))
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit =
    try if (server != null) server.stop()
    finally {
      val logger = LoggerFactory.getLogger("org.llm4s.mcp.MCPServer").asInstanceOf[LBLogger]
      logger.setLevel(previousMcpServerLogLevel)
    }

  describe("MCPServer Integration") {

    it("should be discoverable by MCPClient") {
      val transport = StreamableHTTPTransport(s"http://127.0.0.1:$port/mcp", "test-client")
      val config    = MCPServerConfig("test-server", transport, 5.seconds)
      val client    = new MCPClientImpl(config)

      try {
        val initResult = client.initialize()
        initResult should be(Right(()))

        val toolsResult = client.getTools()
        toolsResult.isRight should be(true)
        val clientTools = toolsResult.fold(_ => Seq.empty, identity)

        clientTools.map(_.name) should contain("ping")

        val args       = ujson.Obj("message" -> "Hello World")
        val execResult = clientTools.find(_.name == "ping").get.execute(args)

        execResult.isRight should be(true)
        val successMsg = execResult.fold(e => fail(s"Expected success but got: ${e.getMessage}"), identity).str
        successMsg should include("Echo: Hello World")

      } finally client.close()
    }
  }

  describe("MCPServer edge cases") {

    it("should return Right(()) when start() is called on an already-running server") {
      server.start() shouldBe Right(())
    }

    it("should return 405 for unsupported HTTP methods") {
      val conn = openConn(port, "/mcp", "PUT")
      conn.getResponseCode shouldBe 405
    }

    it("should return 413 when Content-Length header exceeds payload limit") {
      val statusLine = rawPost(port, "/mcp", "application/json", "10485761", "")
      statusLine should include("413")
    }

    it("should return 200 for a POST notification (no id field)") {
      val conn = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.getOutputStream.write(
        """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""".getBytes("UTF-8")
      )
      conn.getResponseCode shouldBe 200
    }

    it("should return a JSON-RPC parse error for a POST with malformed JSON") {
      val conn = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.getOutputStream.write("not-json-at-all".getBytes("UTF-8"))
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("Parse error")
    }

    it("should return a parse error for a POST body with id but un-parseable as JsonRpcRequest") {
      val conn = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.getOutputStream.write("""{"id":"x","invalid":true}""".getBytes("UTF-8"))
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("Parse error")
    }

    it("should return METHOD_NOT_FOUND for an unknown JSON-RPC method") {
      val sessionId = initializeSession(port)
      val conn      = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
      conn.setRequestProperty("mcp-session-id", sessionId)
      conn.getOutputStream.write(
        """{"jsonrpc":"2.0","id":"m1","method":"unknown/method","params":{}}""".getBytes("UTF-8")
      )
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("Method not found")
    }

    it("should return INVALID_REQUEST for tools/list with an unknown session ID") {
      val conn = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
      conn.setRequestProperty("mcp-session-id", "nonexistent-session-id")
      conn.getOutputStream.write(
        """{"jsonrpc":"2.0","id":"s1","method":"tools/list","params":{}}""".getBytes("UTF-8")
      )
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("Invalid session")
    }

    it("should return TOOL_NOT_FOUND error for an unknown tool name") {
      val sessionId = initializeSession(port)
      val conn      = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
      conn.setRequestProperty("mcp-session-id", sessionId)
      conn.getOutputStream.write(
        """{"jsonrpc":"2.0","id":"t1","method":"tools/call","params":{"name":"does_not_exist","arguments":{}}}"""
          .getBytes("UTF-8")
      )
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("Tool not found")
    }

    it("should return TOOL_EXECUTION_ERROR when a tool returns Left (failure)") {
      val sessionId = initializeSession(port)
      val conn      = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
      conn.setRequestProperty("mcp-session-id", sessionId)
      conn.getOutputStream.write(
        """{"jsonrpc":"2.0","id":"t2","method":"tools/call","params":{"name":"fail_tool","arguments":{}}}"""
          .getBytes("UTF-8")
      )
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("Tool failed")
    }

    it("should render non-String tool result via other.render()") {
      val sessionId = initializeSession(port)
      val conn      = openConn(port, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
      conn.setRequestProperty("mcp-session-id", sessionId)
      conn.getOutputStream.write(
        """{"jsonrpc":"2.0","id":"t3","method":"tools/call","params":{"name":"int_tool","arguments":{}}}"""
          .getBytes("UTF-8")
      )
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("42")
    }

    it("should return 400 for DELETE with no mcp-session-id header") {
      val conn = openConn(port, "/mcp", "DELETE")
      conn.getResponseCode shouldBe 400
    }

    it("should return 404 for DELETE with an unknown session ID") {
      val conn = openConn(port, "/mcp", "DELETE")
      conn.setRequestProperty("mcp-session-id", "ghost-session")
      conn.getResponseCode shouldBe 404
    }

    it("should return 200 for DELETE that terminates an active session") {
      val sessionId = initializeSession(port)
      val conn      = openConn(port, "/mcp", "DELETE")
      conn.setRequestProperty("mcp-session-id", sessionId)
      conn.getResponseCode shouldBe 200
      val body = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      body should include("session_terminated")
    }
  }

  private def openConn(p: Int, path: String, method: String): HttpURLConnection = {
    val conn = URI.create(s"http://127.0.0.1:$p$path").toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod(method)
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(3000)
    conn
  }

  private def rawPost(p: Int, path: String, contentType: String, contentLength: String, body: String): String = {
    val socket = new Socket("127.0.0.1", p)
    try {
      val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "UTF-8"), true)
      out.print(s"POST $path HTTP/1.1\r\n")
      out.print(s"Host: 127.0.0.1:$p\r\n")
      out.print(s"Content-Type: $contentType\r\n")
      out.print(s"Content-Length: $contentLength\r\n")
      out.print("Connection: close\r\n")
      out.print("\r\n")
      if (body.nonEmpty) out.print(body)
      out.flush()
      val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
      reader.readLine()
    } finally socket.close()
  }

  private def initializeSession(p: Int): String = {
    val conn = openConn(p, "/mcp", "POST")
    conn.setDoOutput(true)
    conn.setRequestProperty("Content-Type", "application/json")
    conn.getOutputStream.write(
      """{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        .getBytes("UTF-8")
    )
    conn.getResponseCode shouldBe 200
    conn.getHeaderField("mcp-session-id")
  }
}
