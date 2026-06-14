package org.llm4s.mcp

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ write, macroRW, ReadWriter }

import scala.concurrent.duration._

/**
 * Integration tests for MCP (Model Context Protocol) server connections.
 *
 * Covers three scenarios:
 *  1. Mock-based tests — fully deterministic, run under `sbt test` with no network.
 *  2. Embedded in-process server tests — start a real MCPServer in the JVM, connect
 *     MCPClientImpl, and verify tool listing + invocation end-to-end.
 *  3. Real MCP server smoke test (Option B) — guarded by `MCP_SERVER_URL`; skipped
 *     in CI unless the env var is set.
 */
class MCPServerIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // ===========================================================================
  // Embedded in-process MCP server used by embedded-server tests
  // ===========================================================================

  private var embeddedServer: MCPServer = _
  private var embeddedPort: Int         = -1

  // Tool result type
  case class EchoResult(message: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  case class AddResult(sum: Int)
  object AddResult {
    implicit val rw: ReadWriter[AddResult] = macroRW
  }

  case class ReverseResult(reversed: String)
  object ReverseResult {
    implicit val rw: ReadWriter[ReverseResult] = macroRW
  }

  /** Build the three test tools exposed by the embedded server. */
  private def buildEmbeddedTools(): Seq[ToolFunction[_, _]] = {
    val echoSchema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withProperty(Schema.property("message", Schema.string("Message to echo back")))

    val echoTool = ToolBuilder[Map[String, Any], EchoResult](
      "echo",
      "Echoes back the supplied message",
      echoSchema
    ).withHandler { params =>
      params.getString("message").map(msg => EchoResult(msg))
    }.buildSafe()

    val addSchema = Schema
      .`object`[Map[String, Any]]("Add parameters")
      .withProperty(Schema.property("a", Schema.integer("First operand")))
      .withProperty(Schema.property("b", Schema.integer("Second operand")))

    val addTool = ToolBuilder[Map[String, Any], AddResult](
      "add",
      "Returns the sum of two integers",
      addSchema
    ).withHandler { params =>
      for {
        a <- params.getInt("a")
        b <- params.getInt("b")
      } yield AddResult(a + b)
    }.buildSafe()

    val reverseSchema = Schema
      .`object`[Map[String, Any]]("Reverse parameters")
      .withProperty(Schema.property("text", Schema.string("Text to reverse")))

    val reverseTool = ToolBuilder[Map[String, Any], ReverseResult](
      "reverse",
      "Returns the reversed string",
      reverseSchema
    ).withHandler { params =>
      params.getString("text").map(t => ReverseResult(t.reverse))
    }.buildSafe()

    Seq(echoTool, addTool, reverseTool).flatMap(_.toOption)
  }

  override def beforeAll(): Unit = {
    val tools   = buildEmbeddedTools()
    val options = MCPServerOptions(0, "/mcp", "TestMCPServer", "1.0.0")
    embeddedServer = new MCPServer(options, tools)
    embeddedServer.start().fold(ex => throw ex, _ => ())
    embeddedPort = embeddedServer.boundPort
  }

  override def afterAll(): Unit =
    if (embeddedServer != null) embeddedServer.stop()

  // ===========================================================================
  // Helper: create a client connected to the embedded server
  // ===========================================================================

  private def embeddedClient(): MCPClientImpl = {
    val transport = StreamableHTTPTransport(s"http://127.0.0.1:$embeddedPort/mcp", "test-client")
    val config    = MCPServerConfig("embedded-test-server", transport, 10.seconds)
    new MCPClientImpl(config)
  }

  // ===========================================================================
  // Helper: minimal mock LLM client that drives a single tool call then stops
  // ===========================================================================

  /**
   * A mock LLM client for agent tests.
   *
   * First call returns a tool-call response requesting `toolName` with `args`.
   * Subsequent calls return a plain text completion so the agent terminates.
   */
  private class ToolCallingMock(toolName: String, args: ujson.Value, finalText: String = "Done.")
      extends LLMClient {

    private var callCount = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount += 1
      val result = if (callCount == 1) {
        val toolCall = ToolCall(id = "call-1", name = toolName, arguments = args)
        val message  = AssistantMessage(contentOpt = None, toolCalls = Seq(toolCall))
        Completion(
          id = s"mock-$callCount",
          created = System.currentTimeMillis(),
          content = "",
          model = "mock-model",
          message = message,
          toolCalls = List(toolCall),
          usage = None
        )
      } else {
        val message = AssistantMessage(contentOpt = Some(finalText))
        Completion(
          id = s"mock-$callCount",
          created = System.currentTimeMillis(),
          content = finalText,
          model = "mock-model",
          message = message,
          usage = None
        )
      }
      Right(result)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // ===========================================================================
  // Section 1 — Mock-based MCP integration tests (always CI-safe)
  // ===========================================================================

  "MockMCPClient" should "return a canned tool list without network access" in {
    val cannedTools: Seq[ToolFunction[_, _]] = buildEmbeddedTools()

    val mockClient = new MCPClient {
      override def getTools(): Either[String, Seq[ToolFunction[_, _]]] = Right(cannedTools)
      override def initialize(): Either[String, Unit]                  = Right(())
      override def close(): Unit                                        = ()
    }

    val initResult  = mockClient.initialize()
    val toolsResult = mockClient.getTools()

    initResult.isRight shouldBe true
    toolsResult.isRight shouldBe true
    toolsResult.toOption.get should have size 3
    toolsResult.toOption.get.map(_.name) should contain allOf ("echo", "add", "reverse")
  }

  it should "wrap MCP tools in a ToolRegistry and execute them" in {
    val cannedTools: Seq[ToolFunction[_, _]] = buildEmbeddedTools()

    val mockClient = new MCPClient {
      override def getTools(): Either[String, Seq[ToolFunction[_, _]]] = Right(cannedTools)
      override def initialize(): Either[String, Unit]                  = Right(())
      override def close(): Unit                                        = ()
    }

    val tools    = mockClient.getTools().toOption.get
    val registry = new ToolRegistry(tools)

    registry.tools should have size 3

    val echoRequest = ToolCallRequest("echo", ujson.Obj("message" -> "hello"))
    val echoResult  = registry.execute(echoRequest)

    echoResult.isRight shouldBe true
    val echoJson = echoResult.toOption.get
    echoJson("message").str shouldBe "hello"
  }

  it should "propagate MCP-style error responses (isError=true content)" in {
    val errorClient = new MCPClient {
      override def getTools(): Either[String, Seq[ToolFunction[_, _]]] =
        Left("MCP transport error: connection refused")
      override def initialize(): Either[String, Unit] = Left("initialization failed")
      override def close(): Unit                       = ()
    }

    val initResult  = errorClient.initialize()
    val toolsResult = errorClient.getTools()

    initResult.isLeft shouldBe true
    initResult.swap.toOption.get should include("initialization failed")

    toolsResult.isLeft shouldBe true
    toolsResult.swap.toOption.get should include("connection refused")
  }

  it should "allow an agent to call MCP tools via ToolRegistry with a mock LLM" in {
    val cannedTools: Seq[ToolFunction[_, _]] = buildEmbeddedTools()

    val mockMCPClient = new MCPClient {
      override def getTools(): Either[String, Seq[ToolFunction[_, _]]] = Right(cannedTools)
      override def initialize(): Either[String, Unit]                  = Right(())
      override def close(): Unit                                        = ()
    }

    val mcpTools = mockMCPClient.getTools().toOption.get
    val registry = new ToolRegistry(mcpTools)

    val llmClient = new ToolCallingMock(
      toolName = "echo",
      args = ujson.Obj("message" -> "hello from agent"),
      finalText = "I echoed your message."
    )

    val agent  = new Agent(llmClient)
    val result = agent.run("Please echo a message for me.", registry)

    result.isRight shouldBe true
    val state = result.toOption.get
    state.conversation.messages should not be empty
  }

  it should "handle unknown MCP tool names via ToolRegistry" in {
    val cannedTools: Seq[ToolFunction[_, _]] = buildEmbeddedTools()
    val registry                             = new ToolRegistry(cannedTools)

    val unknownRequest = ToolCallRequest("does_not_exist", ujson.Obj())
    val result         = registry.execute(unknownRequest)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ToolCallError.UnknownFunction]
  }

  // ===========================================================================
  // Section 2 — Embedded in-process MCP server tests (Option A)
  // ===========================================================================

  "MCPClientImpl with embedded server" should "list all 3 expected tools" in {
    val client = embeddedClient()
    val result =
      try {
        client.initialize() match {
          case Left(err) => fail(s"Initialization failed: $err")
          case Right(_)  => ()
        }
        client.getTools()
      } finally client.close()

    result.isRight shouldBe true
    val tools = result.toOption.get
    tools should have size 3
    tools.map(_.name) should contain allOf ("echo", "add", "reverse")
  }

  it should "invoke echo(message='hello') and return 'hello'" in {
    val client = embeddedClient()
    try {
      client.initialize().fold(err => fail(s"Init failed: $err"), _ => ())
      val tools = client.getTools()

      tools.isRight shouldBe true
      val echoTool = tools.toOption.get.find(_.name == "echo").getOrElse(fail("echo tool not found"))

      val execResult = echoTool.execute(ujson.Obj("message" -> "hello"))
      execResult.isRight shouldBe true
      val output = execResult.toOption.get
      output("message").str shouldBe "hello"
    } finally client.close()
  }

  it should "invoke add(a=3, b=4) and return 7" in {
    val client = embeddedClient()
    try {
      client.initialize().fold(err => fail(s"Init failed: $err"), _ => ())
      val tools = client.getTools()

      val addTool = tools.toOption.get.find(_.name == "add").getOrElse(fail("add tool not found"))

      val execResult = addTool.execute(ujson.Obj("a" -> 3, "b" -> 4))
      execResult.isRight shouldBe true
      val output = execResult.toOption.get
      output("sum").num.toInt shouldBe 7
    } finally client.close()
  }

  it should "invoke reverse(text='hello') and return 'olleh'" in {
    val client = embeddedClient()
    try {
      client.initialize().fold(err => fail(s"Init failed: $err"), _ => ())
      val tools = client.getTools()

      val reverseTool = tools.toOption.get.find(_.name == "reverse").getOrElse(fail("reverse tool not found"))

      val execResult = reverseTool.execute(ujson.Obj("text" -> "hello"))
      execResult.isRight shouldBe true
      val output = execResult.toOption.get
      output("reversed").str shouldBe "olleh"
    } finally client.close()
  }

  it should "return correct tool schemas with name and description" in {
    val client = embeddedClient()
    try {
      client.initialize().fold(err => fail(s"Init failed: $err"), _ => ())
      val tools = client.getTools()

      tools.isRight shouldBe true
      val toolSeq = tools.toOption.get

      val echoTool    = toolSeq.find(_.name == "echo").getOrElse(fail("echo not found"))
      val addTool     = toolSeq.find(_.name == "add").getOrElse(fail("add not found"))
      val reverseTool = toolSeq.find(_.name == "reverse").getOrElse(fail("reverse not found"))

      echoTool.description should not be empty
      addTool.description should not be empty
      reverseTool.description should not be empty
    } finally client.close()
  }

  it should "wire MCP tools into MCPToolRegistry and support getAllTools" in {
    val config   = MCPServerConfig.streamableHTTP("embedded-registry-server", s"http://127.0.0.1:$embeddedPort/mcp", 10.seconds)
    val registry = new MCPToolRegistry(
      mcpServers = Seq(config),
      localTools = Seq.empty,
      cacheTTL = 5.minutes,
      initializeOnStartup = false
    )

    val allTools = registry.getAllTools
    allTools should have size 3
    allTools.map(_.name) should contain allOf ("echo", "add", "reverse")

    registry.close()
  }

  it should "support agent full call-return cycle via embedded MCP server" in {
    val client = embeddedClient()
    try {
      client.initialize().fold(err => fail(s"Init failed: $err"), _ => ())
      val tools    = client.getTools()
      val mcpTools = tools.toOption.get
      val registry = new ToolRegistry(mcpTools)

      val llmClient = new ToolCallingMock(
        toolName = "echo",
        args = ujson.Obj("message" -> "end-to-end test"),
        finalText = "Task complete."
      )

      val agent       = new Agent(llmClient)
      val agentResult = agent.run("Echo a test message.", registry)

      agentResult.isRight shouldBe true
      val state = agentResult.toOption.get
      state.conversation.messages.size should be >= 3
    } finally client.close()
  }

  it should "handle missing tool gracefully (TOOL_NOT_FOUND error code)" in {
    val client = embeddedClient()
    try {
      client.initialize().fold(err => fail(s"Init failed: $err"), _ => ())
      val tools    = client.getTools()
      val registry = new ToolRegistry(tools.toOption.get)

      val badRequest = ToolCallRequest("nonexistent_tool", ujson.Obj())
      val result     = registry.execute(badRequest)

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ToolCallError.UnknownFunction]
    } finally client.close()
  }

  // ===========================================================================
  // Section 3 — Real MCP server smoke test (Option B, CI-skipped)
  // ===========================================================================

  "Real MCP server" should "list and invoke tools (skipped if MCP_SERVER_URL not set)" in {
    val mcpServerUrl = Option(System.getenv("MCP_SERVER_URL")).filter(_.nonEmpty)
    assume(mcpServerUrl.isDefined, "MCP_SERVER_URL not set - skipping real MCP server smoke test")

    val config = MCPServerConfig.streamableHTTP(
      name = "real-mcp-server",
      url = mcpServerUrl.get,
      timeout = 30.seconds
    )
    val client = new MCPClientImpl(config)

    val toolsResult =
      try {
        client.initialize() match {
          case Left(err) => fail(s"Real MCP server initialization failed: $err")
          case Right(_)  => ()
        }
        client.getTools()
      } finally client.close()

    toolsResult.isRight shouldBe true
    val tools = toolsResult.toOption.get
    tools should not be empty
  }
}
