package org.llm4s.mcp

import org.llm4s.agent.Agent
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, Socket, URI }
import scala.concurrent.duration._
import scala.util.{ Try, Using }

/**
 * The MCP client against a server llm4s did not write: the official MCP reference server
 * ("everything", `@modelcontextprotocol/server-everything`, built on the TypeScript MCP SDK),
 * reached over Streamable HTTP at `MCP_SERVER_URL`.
 *
 * Every other MCP suite talks to llm4s's own [[MCPServer]] or to a scripted stub, so a
 * misreading of the protocol shared by both ends of llm4s would pass them all. This one checks
 * the handshake, tool discovery with the server's own JSON schemas, invocation, the server's
 * error results, notifications, and an [[Agent]] calling a discovered tool, against a
 * third-party implementation.
 *
 * The CI `integration-tests` job runs the server in a container. The server (2026.8.31) and its
 * whole dependency tree are pinned by `modules/it/mcp-reference-server/package-lock.json` and
 * installed with install scripts disabled; the image is pinned by digest in the workflow. From
 * the repository root:
 * {{{
 * docker run -d -p 3001:3001 -v "$PWD/modules/it/mcp-reference-server:/src:ro" \
 *   node:22-alpine@sha256:<digest from .github/workflows/ci.yml> sh /src/run.sh
 * export MCP_SERVER_URL=http://localhost:3001/mcp
 * sbt "it/testOnly org.llm4s.mcp.MCPReferenceServerSpec"
 * }}}
 * (`MCP_SERVER_SRC=modules/it/mcp-reference-server sh modules/it/mcp-reference-server/run.sh`
 * works without Docker.) Without `MCP_SERVER_URL`, or with nothing listening there, each test is
 * cancelled - or fails, under `LLM4S_IT_STRICT=true`.
 */
@Docker
class MCPReferenceServerSpec extends AnyFlatSpec with Matchers {

  private val serverUrl: Option[String] = sys.env.get("MCP_SERVER_URL").map(_.trim).filter(_.nonEmpty)

  /** The server's URL, once something is listening on its port; cancels (or, strict, fails) otherwise. */
  private def requireServer(): String = {
    Tier.require(serverUrl.isDefined, "MCP_SERVER_URL is not set")
    val url = serverUrl.getOrElse("")
    Tier.require(listening(url), s"No MCP server is listening at MCP_SERVER_URL ($url)")
    url
  }

  private def listening(url: String): Boolean =
    Try {
      val uri  = URI.create(url)
      val port = if (uri.getPort > 0) uri.getPort else if (uri.getScheme == "https") 443 else 80
      Using.resource(new Socket()) { socket =>
        socket.connect(new InetSocketAddress(uri.getHost, port), 2000)
        true
      }
    }.getOrElse(false)

  private def withClient[A](f: MCPClientImpl => A): A = {
    val url    = requireServer()
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("reference", url, 30.seconds))
    Using.resource(new AutoCloseable { def close(): Unit = client.close() }) { _ =>
      client.initialize() shouldBe Right(())
      f(client)
    }
  }

  private def toolNamed(client: MCPClient, name: String): ToolFunction[_, _] =
    client
      .getTools()
      .fold(e => fail(s"getTools failed: ${e.formatted}"), identity)
      .find(_.name == name)
      .getOrElse(fail(s"the reference server did not advertise '$name'"))

  private def parameters(tool: ToolFunction[_, _]): ujson.Value =
    tool.toOpenAITool(strict = false)("function")("parameters")

  private def failureText(result: Either[ToolCallError, ujson.Value]): String =
    result.fold(_.getMessage, v => fail(s"expected a failed call, got $v"))

  "MCPClientImpl against the MCP reference server" should "complete the handshake over Streamable HTTP, without falling back to SSE" in
    withClient(client => client.transport.exists(_.isInstanceOf[StreamableHTTPTransportImpl]) shouldBe true)

  it should "discover echo and get-sum with the input schemas the server declares" in withClient { client =>
    val echo = parameters(toolNamed(client, "echo"))
    echo("properties")("message")("type").str shouldBe "string"
    echo("required").arr.map(_.str).toSet shouldBe Set("message")
    toolNamed(client, "echo").description shouldBe "Echoes back the input string"

    val sum = parameters(toolNamed(client, "get-sum"))
    sum("properties")("a")("type").str shouldBe "number"
    sum("properties")("b")("type").str shouldBe "number"
    sum("required").arr.map(_.str).toSet shouldBe Set("a", "b")
  }

  it should "invoke echo and return the server's text" in withClient { client =>
    toolNamed(client, "echo").execute(ujson.Obj("message" -> "hello")) shouldBe Right(ujson.Str("Echo: hello"))
  }

  it should "invoke get-sum and return the server-computed sum" in withClient { client =>
    toolNamed(client, "get-sum").execute(ujson.Obj("a" -> 2, "b" -> 3)) shouldBe
      Right(ujson.Str("The sum of 2 and 3 is 5."))
  }

  it should "return a tool's structuredContent as the JSON value it is" in withClient { client =>
    val result = toolNamed(client, "get-structured-content").execute(ujson.Obj("location" -> "Chicago"))
    val obj    = result.fold(e => fail(s"get-structured-content failed: ${e.getMessage}"), identity)
    obj.objOpt.isDefined shouldBe true
    obj.obj.keySet should not be empty
  }

  it should "surface the server's validation error as a failed call when a required argument is missing" in
    withClient { client =>
      val message = failureText(toolNamed(client, "echo").execute(ujson.Obj()))
      message should include("Tool call failed")
      message should include("Invalid arguments for tool echo")
    }

  it should "surface the server's error when asked to call a tool it does not have" in withClient { client =>
    val transport = client.transport.getOrElse(fail("no transport after initialize"))
    val response = transport.sendRequest(
      JsonRpcRequest(
        jsonrpc = "2.0",
        id = "unknown-tool",
        method = "tools/call",
        params = Some(ujson.Obj("name" -> "no-such-tool", "arguments" -> ujson.Obj()))
      )
    )
    // The MCP SDK reports an unknown tool either as a JSON-RPC error or as an isError result.
    val reported = response match {
      case Left(error) => error.message
      case Right(r) =>
        r.error.map(_.message).getOrElse {
          r.result.flatMap(_.objOpt).flatMap(_.get("isError")).flatMap(_.boolOpt) shouldBe Some(true)
          r.result.map(_.render()).getOrElse("")
        }
    }
    reported should include("no-such-tool")
  }

  it should "have its notifications accepted, not refused as Not Acceptable" in withClient { client =>
    // The SDK answers 406 to a POST whose Accept header does not list both JSON and an event stream;
    // the initialized notification sent during initialize() would be refused silently (logged, ignored).
    val transport = client.transport.getOrElse(fail("no transport after initialize"))
    transport.sendNotification(JsonRpcNotification("2.0", "notifications/initialized")) shouldBe Right(())
  }

  "MCPToolRegistry against the MCP reference server" should "execute a discovered tool and refuse an unknown one" in {
    val url = requireServer()
    Using.resource(
      new MCPToolRegistry(Seq(MCPServerConfig.streamableHTTP("reference-registry", url, 30.seconds)), Seq.empty)
    ) { registry =>
      (registry.getAllTools.map(_.name) should contain).allOf("echo", "get-sum")
      registry.execute(ToolCallRequest("echo", ujson.Obj("message" -> "via registry"))) shouldBe
        Right(ujson.Str("Echo: via registry"))
      registry.execute(ToolCallRequest("no-such-tool", ujson.Obj())) match {
        case Left(ToolCallError.UnknownFunction(name, available)) =>
          name shouldBe "no-such-tool"
          available should contain("echo")
        case other => fail(s"expected UnknownFunction, got $other")
      }
    }
  }

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
          id = s"scripted-$calls",
          created = 0L,
          content = message.content,
          model = "scripted-model",
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
    val url = requireServer()
    Using.resource(
      new MCPToolRegistry(Seq(MCPServerConfig.streamableHTTP("reference-agent", url, 30.seconds)), Seq.empty)
    ) { registry =>
      val state = (for {
        agent <- Agent.builder("mcp-reference", new OneToolCallLLM(toolName, args)).withTools(registry).build()
        done  <- agent.run("use the MCP tool")
      } yield done).fold(e => fail(s"agent run failed: ${e.formatted}"), identity)
      state.messages.collect { case m: ToolMessage => m }
    }
  }

  "An Agent with tools from the MCP reference server" should "feed the server's result back into the conversation" in {
    val toolMessages = runAgent("echo", ujson.Obj("message" -> "hello from the agent"))
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe "call-1"
    toolMessages.head.content should include("Echo: hello from the agent")
  }

  it should "refuse arguments that break the schema the server declared, without a result" in {
    // The agent validates a call against the tool's schema - here the server's own JSON schema,
    // as discovered - so the missing `b` is reported back to the model rather than summed.
    val toolMessages = runAgent("get-sum", ujson.Obj("a" -> 2))
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe "call-1"
    (toolMessages.head.content should not).include("The sum of")
    toolMessages.head.content should include("$.b: required property missing")
  }
}
