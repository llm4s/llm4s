package org.llm4s.mcp

import org.llm4s.agent.Agent
import org.llm4s.it.tags.Local
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * An [[Agent]] driving tools served by a real in-process [[MCPServer]] over Streamable HTTP.
 *
 * It spans `llm4s-agent` and `llm4s-mcp`, neither of which depends on the other, so it lives
 * here; it needs no external service, hence the Local tier. The LLM is scripted: the model
 * requests one tool call, then stops. The assertions are on what the server computed, never
 * on model text.
 */
@Local
class AgentMCPServerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: MCPServer = _
  private var port: Int         = -1

  override def beforeAll(): Unit = {
    val schema = Schema
      .`object`[Map[String, Any]]("Add parameters")
      .withProperty(Schema.property("a", Schema.integer("First operand")))
      .withProperty(Schema.property("b", Schema.integer("Second operand")))
    val add = ToolBuilder[Map[String, Any], String]("add", "Returns the sum of two integers", schema)
      .withHandler(p =>
        for {
          a <- p.getInt("a")
          b <- p.getInt("b")
        } yield (a + b).toString
      )
      .buildSafe()
      .fold(e => fail(s"could not build tool: ${e.formatted}"), identity)

    server = new MCPServer(MCPServerOptions(0, "/mcp", "AgentMCPServer", "1.0.0"), Seq(add))
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit =
    if (server != null) server.stop()

  /** Requests `toolName(args)` on the first call, then answers with plain text. */
  private class OneToolCallLLM(toolName: String, args: ujson.Value) extends LLMClient {
    private var calls = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls += 1
      val toolCalls = if (calls == 1) Seq(ToolCall("call-1", toolName, args)) else Seq.empty
      val message =
        if (toolCalls.nonEmpty) AssistantMessage(contentOpt = None, toolCalls = toolCalls)
        else AssistantMessage("done")
      Right(
        Completion(
          id = s"mock-$calls",
          created = 0L,
          content = message.content,
          model = "mock-model",
          message = message,
          toolCalls = toolCalls.toList,
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def runAgent(toolName: String, args: ujson.Value): Seq[ToolMessage] = {
    val config   = MCPServerConfig.streamableHTTP("agent-mcp", s"http://127.0.0.1:$port/mcp", 10.seconds)
    val registry = new MCPToolRegistry(Seq(config), Seq.empty, 5.minutes, initializeOnStartup = false)
    try {
      val state = new Agent(new OneToolCallLLM(toolName, args))
        .run("compute", registry)
        .fold(e => fail(s"agent run failed: ${e.formatted}"), identity)
      state.conversation.messages.collect { case m: ToolMessage => m }
    } finally registry.close()
  }

  "An Agent with MCP-served tools" should "feed the server-computed result back into the conversation" in {
    val toolMessages = runAgent("add", ujson.Obj("a" -> 19, "b" -> 23))
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe "call-1"
    toolMessages.head.content should include("42")
  }

  it should "record an error rather than a result when the tool name is unknown to the server" in {
    val toolMessages = runAgent("not_a_tool", ujson.Obj())
    toolMessages should have size 1
    (toolMessages.head.content should not).include("42")
    toolMessages.head.content.toLowerCase should include("not_a_tool")
  }
}
