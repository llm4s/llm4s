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

  it should "fall back when the server refuses initialize with any other 4xx, such as 400" in {
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 400, "{}")) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("old", url + "/mcp", 5.seconds))
      try failure(client.initialize()) should startWith("Failed to connect with both transports")
      finally client.close()
    }
  }

  it should "report a 401 as it is, without trying HTTP+SSE" in {
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 401, "{}")) { url =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("locked", url + "/mcp", 5.seconds))
      try {
        val message = failure(client.initialize())
        message should include("HTTP error 401")
        (message should not).include("both transports")
      } finally client.close()
    }
  }

  "MCPClientImpl.fallsBackToLegacy" should "hold for every 4xx except 401 and 403, and for nothing else" in {
    Seq(400, 404, 405, 406, 415, 422, 499).foreach(s => withClue(s)(MCPClientImpl.fallsBackToLegacy(s) shouldBe true))
    Seq(200, 202, 301, 401, 403, 500, 503).foreach(s => withClue(s)(MCPClientImpl.fallsBackToLegacy(s) shouldBe false))
  }
}
