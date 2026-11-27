package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.security.Secret
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.net.Socket
import java.nio.charset.StandardCharsets
import scala.util.Using

/**
 * DNS rebinding (#3069): a web page that rebinds its own name to 127.0.0.1 reaches a loopback [[MCPServer]]
 * same-origin, with its own name in `Host` and its own origin in `Origin`. The server refuses (403) a `Host` that
 * is not a loopback name, or one of `allowedHosts`, and an `Origin` that is neither loopback nor one of
 * `allowedOrigins`, on every route; and a POST whose body is not declared `application/json` (415).
 *
 * The requests are written to a socket: the JDK's HTTP clients do not let a caller set `Host`.
 */
class MCPServerOriginSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  private var loopback: MCPServer = _
  private var named: MCPServer    = _

  override def beforeAll(): Unit = {
    LoggerFactory.getLogger(classOf[MCPServer]).asInstanceOf[LBLogger].setLevel(Level.OFF)
    loopback = new MCPServer(MCPServerOptions(0, "/mcp", "Loopback", "1.0"), Seq.empty)
    named = new MCPServer(
      MCPServerOptions(
        0,
        "/mcp",
        "Named",
        "1.0",
        apiKey = Some(Secret("k")),
        allowedHosts = Set("mcp.example.com"),
        allowedOrigins = Set("https://app.example.com")
      ),
      Seq.empty
    )
    loopback.start().fold(e => throw e, _ => ())
    named.start().fold(e => throw e, _ => ())
  }

  override def afterAll(): Unit = Seq(loopback, named).filter(_ != null).foreach(_.stop())

  private val list = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""

  /** The status code of a request written as is, with `headers` (each `Name: value`). */
  private def status(
    port: Int,
    method: String,
    path: String,
    headers: Seq[String],
    body: String = ""
  ): Int =
    Using.resource(new Socket("127.0.0.1", port)) { socket =>
      socket.setSoTimeout(5000)
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      val head =
        (s"$method $path HTTP/1.1" +: headers) ++
          (if (method == "POST") Seq(s"Content-Length: ${bytes.length}") else Nil) :+ "Connection: close"
      val out = socket.getOutputStream
      out.write((head.mkString("\r\n") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8))
      out.write(bytes)
      out.flush()
      val statusLine =
        new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8)).readLine()
      statusLine.split(" ")(1).toInt
    }

  private def post(port: Int, headers: String*): Int = status(port, "POST", "/mcp", headers, list)
  private def sse(port: Int, headers: String*): Int  = status(port, "GET", "/mcp/sse", headers)
  private def messages(port: Int, headers: String*): Int =
    status(port, "POST", "/mcp/messages?sessionId=none", headers, list)

  private def lp   = loopback.boundPort
  private val json = "Content-Type: application/json"

  describe("a loopback server without allowedHosts") {
    it("serves a loopback Host, with or without a loopback Origin") {
      post(lp, s"Host: 127.0.0.1:$lp", json) shouldBe 200
      post(lp, s"Host: localhost:$lp", json) shouldBe 200
      post(lp, s"Host: [::1]:$lp", json) shouldBe 200
      post(lp, "Host: localhost", json, s"Origin: http://localhost:3000") shouldBe 200
      post(lp, "Host: localhost", json, s"Origin: http://127.0.0.1:$lp") shouldBe 200
    }

    it("refuses a rebound Host on every route") {
      post(lp, "Host: evil.example", json) shouldBe 403
      post(lp, s"Host: evil.example:$lp", json) shouldBe 403
      post(lp, "Host: localhost.evil.example", json) shouldBe 403
      sse(lp, "Host: evil.example") shouldBe 403
      messages(lp, "Host: evil.example", json) shouldBe 403
      status(lp, "DELETE", "/mcp", Seq("Host: evil.example", "mcp-session-id: x")) shouldBe 403
    }

    it("refuses a foreign or opaque Origin on every route, even with a loopback Host") {
      post(lp, "Host: localhost", json, "Origin: http://evil.example") shouldBe 403
      post(lp, "Host: localhost", json, "Origin: http://localhost.evil.example") shouldBe 403
      post(lp, "Host: localhost", json, "Origin: null") shouldBe 403
      sse(lp, "Host: localhost", "Origin: http://evil.example") shouldBe 403
      messages(lp, "Host: localhost", json, "Origin: http://evil.example") shouldBe 403
    }

    it("refuses a request without a Host") {
      status(lp, "POST", "/mcp", Seq(json), list) shouldBe 403
    }

    it("refuses a POST that does not declare a JSON body, on both POST routes") {
      post(lp, "Host: localhost", "Content-Type: text/plain") shouldBe 415
      post(lp, "Host: localhost") shouldBe 415
      messages(lp, "Host: localhost", "Content-Type: text/plain") shouldBe 415
      post(lp, "Host: localhost", "Content-Type: application/json; charset=utf-8") shouldBe 200
      post(lp, "Host: localhost", "Content-Type: Application/JSON") shouldBe 200
    }
  }

  describe("a server with allowedHosts and allowedOrigins") {
    def np   = named.boundPort
    val auth = "Authorization: Bearer k"

    it("serves its declared name and origin, and loopback ones") {
      post(np, "Host: mcp.example.com", json, auth) shouldBe 200
      post(np, s"Host: MCP.example.com:$np", json, auth, "Origin: https://app.example.com") shouldBe 200
      post(np, "Host: localhost", json, auth) shouldBe 200
    }

    it("refuses any other name or origin, before checking the key") {
      post(np, "Host: other.example.com", json, auth) shouldBe 403
      post(np, "Host: mcp.example.com", json, auth, "Origin: https://evil.example") shouldBe 403
      post(np, "Host: other.example.com", json) shouldBe 403
    }
  }
}
