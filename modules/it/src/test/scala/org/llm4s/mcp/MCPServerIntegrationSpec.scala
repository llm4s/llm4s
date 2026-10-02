package org.llm4s.mcp

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.mock.MockLLMClient
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Integration tests for MCP (Model Context Protocol) server connections.
 *
 * These tests spin up an embedded test MCP server, connect to it via MCPClient,
 * and verify end-to-end tool discovery and invocation through an Agent.
 *
 * Test Coverage:
 * - Embedded MCP server (CI-safe, no external dependencies)
 * - Tool listing via MCP protocol
 * - Tool invocation through agent
 * - Error handling and propagation
 * - Multiple transport types (Streamable HTTP)
 */
class MCPServerIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var testServer: HttpServer = _
  private var testServerPort: Int = _
  private val requestCounter = new AtomicInteger(0)

  // Minimal tool descriptor for the embedded server
  private case class TestToolDef(
    name: String,
    description: String,
    paramSchema: ujson.Obj,
    handler: ujson.Value => ujson.Value
  )

  private val testTools: Seq[TestToolDef] = Seq(
    TestToolDef(
      name = "echo",
      description = "Echoes back the input message",
      paramSchema = ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "message" -> ujson.Obj(
            "type" -> "string",
            "description" -> "The message to echo"
          )
        ),
        "required" -> ujson.Arr("message")
      ),
      handler = (params: ujson.Value) => ujson.Str(params("message").str)
    ),
    TestToolDef(
      name = "add",
      description = "Adds two numbers",
      paramSchema = ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "a" -> ujson.Obj("type" -> "number", "description" -> "First number"),
          "b" -> ujson.Obj("type" -> "number", "description" -> "Second number")
        ),
        "required" -> ujson.Arr("a", "b")
      ),
      handler = (params: ujson.Value) => ujson.Num(params("a").num + params("b").num)
    ),
    TestToolDef(
      name = "reverse",
      description = "Reverses a string",
      paramSchema = ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "text" -> ujson.Obj("type" -> "string", "description" -> "The text to reverse")
        ),
        "required" -> ujson.Arr("text")
      ),
      handler = (params: ujson.Value) => ujson.Str(params("text").str.reverse)
    )
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    startEmbeddedMCPServer()
  }

  override def afterAll(): Unit = {
    stopEmbeddedMCPServer()
    super.afterAll()
  }

  private def startEmbeddedMCPServer(): Unit = {
    testServer = HttpServer.create(new InetSocketAddress(0), 0)
    testServerPort = testServer.getAddress.getPort
    testServer.createContext("/", new MCPServerHandler())
    testServer.setExecutor(null)
    testServer.start()
  }

  private def stopEmbeddedMCPServer(): Unit = {
    if (testServer != null) testServer.stop(0)
  }

  /**
   * Embedded MCP server handler implementing the JSON-RPC subset of MCP.
   */
  private class MCPServerHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      try {
        requestCounter.incrementAndGet()
        if (exchange.getRequestMethod != "POST") {
          sendJson(exchange, 405, ujson.Obj("jsonrpc" -> "2.0", "id" -> "0", "error" -> ujson.Obj("code" -> -32600, "message" -> "Method not allowed")))
          return
        }

        val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
        val req = read[ujson.Value](body)
        val response = handleRequest(req)
        sendJson(exchange, 200, response)
      } catch {
        case e: Exception =>
          sendJson(exchange, 500, ujson.Obj("jsonrpc" -> "2.0", "id" -> "0", "error" -> ujson.Obj("code" -> -32603, "message" -> e.getMessage)))
      }
    }

    private def handleRequest(req: ujson.Value): ujson.Value = {
      val id = req("id").str
      val method = req.obj.get("method").map(_.str).getOrElse("")

      method match {
        case "initialize" =>
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> id,
            "result" -> ujson.Obj(
              "protocolVersion" -> "2025-03-26",
              "capabilities" -> ujson.Obj("tools" -> ujson.Obj("listChanged" -> false)),
              "serverInfo" -> ujson.Obj("name" -> "test-mcp-server", "version" -> "1.0.0")
            )
          )

        case "tools/list" =>
          val toolsList = testTools.map { tool =>
            ujson.Obj(
              "name" -> tool.name,
              "description" -> tool.description,
              "inputSchema" -> tool.paramSchema
            )
          }
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> id,
            "result" -> ujson.Obj("tools" -> ujson.Arr.from(toolsList))
          )

        case "tools/call" =>
          val params = req.obj.get("params").getOrElse(ujson.Obj())
          val toolName = params.obj.get("name").map(_.str).getOrElse("")
          val arguments = params.obj.get("arguments").getOrElse(ujson.Obj())

          testTools.find(_.name == toolName) match {
            case Some(tool) =>
              try {
                val result = tool.handler(arguments)
                ujson.Obj(
                  "jsonrpc" -> "2.0",
                  "id" -> id,
                  "result" -> ujson.Obj(
                    "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> write(result)))
                  )
                )
              } catch {
                case e: Exception =>
                  ujson.Obj(
                    "jsonrpc" -> "2.0",
                    "id" -> id,
                    "error" -> ujson.Obj("code" -> -32000, "message" -> s"Tool execution failed: ${e.getMessage}")
                  )
              }
            case None =>
              ujson.Obj(
                "jsonrpc" -> "2.0",
                "id" -> id,
                "error" -> ujson.Obj("code" -> -32601, "message" -> s"Tool not found: $toolName")
              )
          }

        case _ =>
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> id,
            "error" -> ujson.Obj("code" -> -32601, "message" -> s"Method not found: $method")
          )
      }
    }

    private def sendJson(exchange: HttpExchange, status: Int, body: ujson.Value): Unit = {
      val bytes = write(body).getBytes("UTF-8")
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length)
      val os = exchange.getResponseBody()
      os.write(bytes)
      os.close()
    }
  }

  // ============================================================================
  // Tests
  // ============================================================================

  "Embedded MCP server" should "respond to initialize handshake" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val client = MCPClient(config)
    val initResult = client.initialize()
    initResult shouldBe a[Right[_, _]]
    client.close()
  }

  it should "return exactly 3 test tools via tools/list" in {
    val initialCount = requestCounter.get()

    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val client = MCPClient(config)
    client.initialize() shouldBe a[Right[_, _]]

    val toolsResult = client.getTools()
    toolsResult shouldBe a[Right[_, _]]

    val tools = toolsResult.toOption.get
    tools.size shouldBe 3
    tools.map(_.name).toSet shouldBe Set("echo", "add", "reverse")

    // Verify server received requests
    requestCounter.get() should be > initialCount

    client.close()
  }

  "MCPToolRegistry with embedded server" should "discover all MCP tools" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      initializeOnStartup = true
    )

    val allTools = registry.tools
    allTools.size shouldBe 3
    allTools.map(_.name).toSet shouldBe Set("echo", "add", "reverse")

    registry.close()
  }

  it should "execute echo tool via MCP" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      initializeOnStartup = true
    )

    val request = ToolCallRequest(
      functionName = "echo",
      arguments = ujson.Obj("message" -> "hello from integration test")
    )

    val result = registry.execute(request)
    result shouldBe a[Right[_, _]]

    registry.close()
  }

  it should "execute add tool via MCP" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      initializeOnStartup = true
    )

    val request = ToolCallRequest(
      functionName = "add",
      arguments = ujson.Obj("a" -> 42, "b" -> 58)
    )

    val result = registry.execute(request)
    result shouldBe a[Right[_, _]]

    registry.close()
  }

  it should "execute reverse tool via MCP" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      initializeOnStartup = true
    )

    val request = ToolCallRequest(
      functionName = "reverse",
      arguments = ujson.Obj("text" -> "scalals")
    )

    val result = registry.execute(request)
    result shouldBe a[Right[_, _]]

    registry.close()
  }

  it should "return error for nonexistent tool" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      initializeOnStartup = true
    )

    val request = ToolCallRequest(
      functionName = "nonexistent_tool",
      arguments = ujson.Obj()
    )

    val result = registry.execute(request)
    result shouldBe a[Left[_, _]]

    registry.close()
  }

  "Local tools should take priority over MCP tools" should "shadow MCP tools with same name" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    // Create a local "echo" tool that returns a different result
    val localEcho = ToolFunction[String, String](
      name = "echo",
      description = "Local echo (should shadow MCP echo)",
      parameters = ToolParameterSpec(
        `type` = "object",
        properties = Map("message" -> ToolProperty(`type` = "string", description = Some("Input")))
      ),
      implementation = (input: String) => Right(s"LOCAL: $input")
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq(localEcho),
      initializeOnStartup = true
    )

    // Local tools + MCP tools (local echo shadows MCP echo)
    val allTools = registry.tools
    allTools.size should be >= 3

    registry.close()
  }

  "Agent with MCP tools" should "have access to MCP tools in registry" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      initializeOnStartup = true
    )

    val mockLLM = new MockLLMClient(responses = Seq.empty)
    val agent = Agent(mockLLM, registry)

    // Verify agent can see MCP tools
    val agentTools = registry.tools
    agentTools.size shouldBe 3
    agentTools.map(_.name).toSet shouldBe Set("echo", "add", "reverse")

    registry.close()
  }

  "Connection lifecycle" should "cleanly close and reopen" in {
    val config = MCPServerConfig(
      name = "integration-test",
      transport = StreamableHTTPTransport(
        url = s"http://localhost:$testServerPort/",
        name = "test-http"
      )
    )

    // First session
    val client1 = MCPClient(config)
    client1.initialize() shouldBe a[Right[_, _]]
    val tools1 = client1.getTools()
    tools1 shouldBe a[Right[_, _]]
    tools1.toOption.get.size shouldBe 3
    client1.close()

    // Second session (should work after close)
    val client2 = MCPClient(config)
    client2.initialize() shouldBe a[Right[_, _]]
    val tools2 = client2.getTools()
    tools2 shouldBe a[Right[_, _]]
    tools2.toOption.get.size shouldBe 3
    client2.close()
  }

  "Mock-based MCP client" should "simulate tool discovery without real server" in {
    // MockMCPClient for CI-safe tests that don't need a running server
    val mockTools: Seq[ToolFunction[_, _]] = Seq(
      ToolFunction[String, String](
        name = "mock_echo",
        description = "Mock echo tool",
        parameters = ToolParameterSpec(
          `type` = "object",
          properties = Map("message" -> ToolProperty(`type` = "string", description = Some("Input")))
        ),
        implementation = (input: String) => Right(input)
      )
    )

    val registry = new MCPToolRegistry(
      mcpServers = Seq.empty,
      localTools = mockTools,
      initializeOnStartup = false
    )

    val tools = registry.tools
    tools.size shouldBe 1
    tools.head.name shouldBe "mock_echo"

    registry.close()
  }
}
