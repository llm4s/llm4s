package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

/**
 * Tests for MCPServerConfig and MCPTransport types
 */
class MCPServerConfigSpec extends AnyFlatSpec with Matchers {

  // ============ MCPServerConfig ============

  "MCPServerConfig" should "have default timeout of 30 seconds" in {
    val config = MCPServerConfig("test", StdioTransport(Seq("echo"), "test"))

    config.timeout shouldBe 30.seconds
  }

  it should "allow custom timeout" in {
    val config = MCPServerConfig("test", StdioTransport(Seq("echo"), "test"), 60.seconds)

    config.timeout shouldBe 60.seconds
  }

  // ============ MCPServerConfig.stdio ============

  "MCPServerConfig.stdio" should "create stdio transport config" in {
    val config = MCPServerConfig.stdio("playwright", Seq("npx", "@playwright/mcp@latest"))

    config.name shouldBe "playwright"
    config.transport shouldBe a[StdioTransport]
    config.transport.asInstanceOf[StdioTransport].command shouldBe Seq("npx", "@playwright/mcp@latest")
    config.timeout shouldBe 30.seconds
  }

  it should "accept custom timeout" in {
    val config = MCPServerConfig.stdio("test", Seq("echo"), 120.seconds)

    config.timeout shouldBe 120.seconds
  }

  it should "pass name to transport" in {
    val config    = MCPServerConfig.stdio("my-server", Seq("cmd"))
    val transport = config.transport.asInstanceOf[StdioTransport]

    transport.name shouldBe "my-server"
  }

  // ============ MCPServerConfig.streamableHTTP ============

  "MCPServerConfig.streamableHTTP" should "create streamable HTTP transport config" in {
    val config = MCPServerConfig.streamableHTTP("remote-server", "https://mcp.example.com/api")

    config.name shouldBe "remote-server"
    config.transport shouldBe a[StreamableHTTPTransport]
    config.transport.asInstanceOf[StreamableHTTPTransport].url shouldBe "https://mcp.example.com/api"
    config.timeout shouldBe 30.seconds
  }

  it should "accept custom timeout" in {
    val config = MCPServerConfig.streamableHTTP("test", "http://localhost:8080", 45.seconds)

    config.timeout shouldBe 45.seconds
  }

  it should "pass name to transport" in {
    val config    = MCPServerConfig.streamableHTTP("http-server", "http://localhost")
    val transport = config.transport.asInstanceOf[StreamableHTTPTransport]

    transport.name shouldBe "http-server"
  }

  // ============ MCPServerConfig.sse ============

  "MCPServerConfig.sse" should "create SSE transport config" in {
    val config = MCPServerConfig.sse("legacy-server", "https://mcp.example.com/sse")

    config.name shouldBe "legacy-server"
    config.transport shouldBe a[SSETransport]
    config.transport.asInstanceOf[SSETransport].url shouldBe "https://mcp.example.com/sse"
    config.timeout shouldBe 30.seconds
  }

  it should "accept custom timeout" in {
    val config = MCPServerConfig.sse("test", "http://localhost:8080/sse", 90.seconds)

    config.timeout shouldBe 90.seconds
  }

  it should "pass name to transport" in {
    val config    = MCPServerConfig.sse("sse-server", "http://localhost/sse")
    val transport = config.transport.asInstanceOf[SSETransport]

    transport.name shouldBe "sse-server"
  }

  // ============ Transport Types ============

  "StdioTransport" should "store command and name" in {
    val transport = StdioTransport(Seq("node", "server.js"), "node-server")

    transport.command shouldBe Seq("node", "server.js")
    transport.name shouldBe "node-server"
  }

  it should "be sealed under MCPTransport" in {
    val transport: MCPTransport = StdioTransport(Seq("cmd"), "test")

    transport shouldBe a[MCPTransport]
  }

  "SSETransport" should "store url and name" in {
    val transport = SSETransport("http://localhost:3000/sse", "local-sse")

    transport.url shouldBe "http://localhost:3000/sse"
    transport.name shouldBe "local-sse"
  }

  it should "be sealed under MCPTransport" in {
    val transport: MCPTransport = SSETransport("http://localhost", "test")

    transport shouldBe a[MCPTransport]
  }

  "StreamableHTTPTransport" should "store url and name" in {
    val transport = StreamableHTTPTransport("http://localhost:3000/mcp", "local-http")

    transport.url shouldBe "http://localhost:3000/mcp"
    transport.name shouldBe "local-http"
  }

  it should "be sealed under MCPTransport" in {
    val transport: MCPTransport = StreamableHTTPTransport("http://localhost", "test")

    transport shouldBe a[MCPTransport]
  }

  // ============ Pattern Matching ============

  "MCPTransport" should "support exhaustive pattern matching" in {
    def describeTransport(transport: MCPTransport): String = transport match {
      case StdioTransport(cmd, name)          => s"stdio:$name:${cmd.mkString(",")}"
      case SSETransport(url, name)            => s"sse:$name:$url"
      case StreamableHTTPTransport(url, name) => s"http:$name:$url"
    }

    describeTransport(StdioTransport(Seq("cmd"), "test")) shouldBe "stdio:test:cmd"
    describeTransport(SSETransport("http://a", "b")) shouldBe "sse:b:http://a"
    describeTransport(StreamableHTTPTransport("http://c", "d")) shouldBe "http:d:http://c"
  }
}
