package org.llm4s.mcp

import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ServerSocket
import scala.concurrent.duration.*
import scala.util.Using

/**
 * When `MCPClientImpl` falls back from Streamable HTTP to HTTP+SSE.
 *
 * The fallback is for a server that does not speak the newer transport (404 or 405). It once read that off the
 * error text with `contains("404")`, and a refused-connection message carries the URL: a port such as 40413 or
 * 40585 made a dead server look like one needing the fallback, and the failure then depended on which ephemeral
 * port the operating system happened to hand out.
 */
class MCPTransportFallbackSpec extends AnyFlatSpec with Matchers {

  private def failure[A](result: Result[A]): String = result.left.getOrElse(fail("expected a Left")).message

  /**
   * A URL nothing listens on, on a port whose digits contain `digits`: bound once to prove it is free, then
   * closed. Ports are tried in a range that holds the digits at its front, so the choice is deterministic.
   */
  private def deadUrlWithPortContaining(digits: String): String = {
    val candidates = (40000 to 41999).filter(_.toString.contains(digits)) ++ (4000 to 4999)
      .filter(_.toString.contains(digits))
    val free = candidates.find(port => Using(new ServerSocket(port))(_ => ()).isSuccess)
    s"http://127.0.0.1:${free.getOrElse(fail(s"no free port containing $digits"))}/mcp"
  }

  "MCPClientImpl" should "not mistake a port containing 404 for a 404 from the server" in {
    val url    = deadUrlWithPortContaining("404")
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("dead", url, 2.seconds))
    try {
      val message = failure(client.initialize())
      message should include("connection failed")
      (message should not).include("both transports")
    } finally client.close()
  }

  it should "not mistake a port containing 405 for a 405 from the server" in {
    val url    = deadUrlWithPortContaining("405")
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("dead", url, 2.seconds))
    try {
      val message = failure(client.initialize())
      message should include("connection failed")
      (message should not).include("both transports")
    } finally client.close()
  }

  it should "still fall back when the server answers 404" in {
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 404, "{}")) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("missing", url + "/mcp", 5.seconds))
      try failure(client.initialize()) should startWith("Failed to connect with both transports")
      finally client.close()
    }
  }

  it should "still fall back when the server answers 405" in {
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 405, "{}")) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("rejecting", url + "/mcp", 5.seconds))
      try failure(client.initialize()) should startWith("Failed to connect with both transports")
      finally client.close()
    }
  }

  it should "not fall back for another HTTP error that happens to mention 404 in its body" in {
    LocalProviderTestServer.withServer("/")(ex =>
      LocalProviderTestServer.sendJsonResponse(ex, 500, """{"error":"upstream returned 404 and 405"}""")
    ) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("broken", url + "/mcp", 5.seconds))
      try {
        val message = failure(client.initialize())
        message should include("HTTP error 500")
        (message should not).include("both transports")
      } finally client.close()
    }
  }

  "MCPClientImpl.isUnsupportedTransport" should "accept what the transport says for a 404 and for a 405" in {
    MCPClientImpl.isUnsupportedTransport("Transport error: HTTP error 404: not found") shouldBe true
    MCPClientImpl.isUnsupportedTransport("Transport error: HTTP error 405: nope") shouldBe true
    MCPClientImpl.isUnsupportedTransport(
      "Transport error: Server does not support Streamable HTTP transport (405 Method Not Allowed)"
    ) shouldBe true
  }

  it should "refuse a message that only contains the digits" in {
    Seq(
      "Transport error: POST http://127.0.0.1:40413/mcp: connection failed: ConnectException",
      "Transport error: POST http://127.0.0.1:40585/mcp: connection failed: ConnectException",
      "Transport error: POST http://127.0.0.1:8080/mcp: request timed out after 2 seconds",
      "Transport error: HTTP error 500: upstream returned 404",
      "Transport error: HTTP error 4040: not a status",
      "Transport error: HTTP error 401: unauthorized",
      "Transport error: MCP session expired, client should reinitialize",
      "JSON-RPC Error -32601: Method not found",
      ""
    ).foreach(message => withClue(message)(MCPClientImpl.isUnsupportedTransport(message) shouldBe false))
  }
}
