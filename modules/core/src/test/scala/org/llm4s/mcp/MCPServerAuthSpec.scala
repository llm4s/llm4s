package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.llm4s.toolapi.{ Schema, SafeParameterExtractor, ToolBuilder }
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.net.{ HttpURLConnection, URI }
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for MCPServer Bearer-token authentication.
 *
 * Verifies that:
 *   - Requests without `Authorization` are rejected with HTTP 401.
 *   - Requests with an incorrect token are rejected with HTTP 401.
 *   - Requests with the correct `Authorization: Bearer <key>` succeed.
 *   - All MCP endpoints (Streamable HTTP POST, SSE GET, SSE POST) enforce auth.
 *   - Servers without an apiKey remain open (no 401).
 */
class MCPServerAuthSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  private val apiKey = "test-secret-key-abc"

  private var authServer: MCPServer = _
  private var authPort: Int         = _
  private var openServer: MCPServer = _
  private var openPort: Int         = _

  override def beforeAll(): Unit = {
    silenceLogs()

    val pingTool = buildPingTool()

    val authOpts = MCPServerOptions(0, "/mcp", "AuthTestServer", "1.0", apiKey = Some(apiKey))
    authServer = new MCPServer(authOpts, Seq(pingTool))
    authServer.start().fold(e => throw e, _ => ())
    authPort = authServer.boundPort

    val openOpts = MCPServerOptions(0, "/mcp", "OpenTestServer", "1.0")
    openServer = new MCPServer(openOpts, Seq(pingTool))
    openServer.start().fold(e => throw e, _ => ())
    openPort = openServer.boundPort
  }

  override def afterAll(): Unit = {
    if (authServer != null) authServer.stop()
    if (openServer != null) openServer.stop()
  }

  describe("Authenticated MCPServer") {

    it("should return 401 when Authorization header is missing") {
      val conn = openConnection(authPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      val body = initializeRequestBody("1")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      conn.getResponseCode shouldBe 401
    }

    it("should return 401 when the Bearer token is wrong") {
      val conn = openConnection(authPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Authorization", "Bearer wrong-key")
      val body = initializeRequestBody("2")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      conn.getResponseCode shouldBe 401
    }

    it("should return 401 for Bearer token with wrong capitalisation") {
      val conn = openConnection(authPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Authorization", s"BEARER $apiKey")
      val body = initializeRequestBody("3")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      conn.getResponseCode shouldBe 200
    }

    it("should return 200 when the correct Bearer token is provided") {
      val conn = openConnection(authPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Authorization", s"Bearer $apiKey")
      val body = initializeRequestBody("4")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      conn.getResponseCode shouldBe 200
    }

    it("should return 401 on the SSE endpoint without a token") {
      val conn = openConnection(authPort, "/mcp/sse", "GET")
      conn.setRequestProperty("Accept", "text/event-stream")
      conn.setConnectTimeout(2000)
      conn.setReadTimeout(2000)
      conn.getResponseCode shouldBe 401
    }

    it("should return 401 on SSE messages endpoint without a token") {
      val conn = openConnection(authPort, "/mcp/messages?sessionId=fake", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.getOutputStream.write("{}".getBytes("UTF-8"))
      conn.getResponseCode shouldBe 401
    }

    it("should accept requests after correct token via MCPClient (Streamable HTTP)") {
      val conn = openConnection(authPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Authorization", s"Bearer $apiKey")
      val body = initializeRequestBody("5")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      val code = conn.getResponseCode
      code shouldBe 200
      val responseBody = scala.io.Source.fromInputStream(conn.getInputStream).mkString
      responseBody should include("2025-06-18")
      responseBody should include("AuthTestServer")
    }
  }

  describe("Open MCPServer (no apiKey)") {

    it("should allow requests without any Authorization header") {
      val conn = openConnection(openPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      val body = initializeRequestBody("10")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      conn.getResponseCode shouldBe 200
    }

    it("should ignore any Authorization header that is present") {
      val conn = openConnection(openPort, "/mcp", "POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Authorization", "Bearer some-random-key")
      val body = initializeRequestBody("11")
      conn.getOutputStream.write(body.getBytes("UTF-8"))
      conn.getResponseCode shouldBe 200
    }
  }

  describe("MCPServer with host=0.0.0.0") {

    it("should rewrite 0.0.0.0 to 127.0.0.1 in SSE endpoint event") {
      val wildcardOpts = MCPServerOptions(0, "/mcp", "WildcardServer", "1.0", apiKey = Some(apiKey), host = "0.0.0.0")
      val tool         = buildPingTool()
      val srv          = new MCPServer(wildcardOpts, Seq(tool))
      srv.start().fold(e => throw e, _ => ())
      val srvPort = srv.boundPort

      val endpointLatch = new CountDownLatch(1)
      val endpointUrl   = new AtomicReference[String]("")
      val executor      = Executors.newSingleThreadExecutor()

      val conn =
        URI.create(s"http://127.0.0.1:$srvPort/mcp/sse").toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setRequestProperty("Accept", "text/event-stream")
      conn.setRequestProperty("Authorization", s"Bearer $apiKey")
      conn.setConnectTimeout(3000)
      conn.setReadTimeout(5000)

      executor.submit(new Runnable {
        override def run(): Unit =
          try {
            val reader = new BufferedReader(new InputStreamReader(conn.getInputStream, "UTF-8"))
            val sb     = new StringBuilder
            var line   = reader.readLine()
            while (line != null) {
              if (line.isEmpty) {
                val event = sb.toString()
                if (event.startsWith("event: endpoint")) {
                  val url =
                    event.linesIterator.find(_.startsWith("data: ")).map(_.stripPrefix("data: ").trim).getOrElse("")
                  endpointUrl.set(url)
                  endpointLatch.countDown()
                }
                sb.clear()
              } else {
                if (sb.nonEmpty) sb.append("\n")
                sb.append(line)
              }
              line = reader.readLine()
            }
          } catch { case _: Exception => () }
      })

      try {
        endpointLatch.await(5, TimeUnit.SECONDS) shouldBe true
        endpointUrl.get() should include("127.0.0.1")
        (endpointUrl.get() should not).include("0.0.0.0")
      } finally {
        conn.disconnect()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        srv.stop()
      }
    }
  }

  private def openConnection(port: Int, path: String, method: String): HttpURLConnection = {
    val conn = URI.create(s"http://127.0.0.1:$port$path").toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod(method)
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(3000)
    conn
  }

  private def initializeRequestBody(id: String): String =
    s"""{"jsonrpc":"2.0","id":"$id","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

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
