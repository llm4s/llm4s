package org.llm4s.mcp

import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolCallInfo }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * An agent's tool call reaches an MCP server with its idempotency key in `tools/call` `_meta`, never in the arguments,
 * and an llm4s server hands it to its tool (#1740).
 */
class MCPToolCallMetaSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val call = ToolCallInfo(toolCallId = "call_1", idempotencyKey = "key-1")

  private val whoami = ToolBuilder[Map[String, Any], String]("whoami", "The call it runs", Schema.`object`("args"))
    .withHandler(params => Right(params.call.fold("none")(c => s"${c.toolCallId}|${c.idempotencyKey}")))
    .buildSafe()
    .fold(e => fail(e.formatted), identity)

  private var server: MCPServer = _

  override def beforeAll(): Unit = {
    server = new MCPServer(MCPServerOptions(0, "/mcp", "MetaServer", "1.0.0"), Seq(whoami))
    server.start().fold(e => throw e, _ => ())
  }

  override def afterAll(): Unit = if (server != null) server.stop()

  private def remoteWhoami = {
    val transport = StreamableHTTPTransport(s"http://127.0.0.1:${server.boundPort}/mcp", "meta-client")
    val client    = new MCPClientImpl(MCPServerConfig("meta", transport, 5.seconds))
    client.getTools().fold(e => fail(e.toString), identity).find(_.name == "whoami").getOrElse(fail("no whoami"))
  }

  "An llm4s MCP client and server" should "carry an agent call's id and key to the server's tool" in {
    remoteWhoami.execute(ujson.Obj(), call) shouldBe Right(ujson.Str("call_1|key-1"))
  }

  they should "run a plain tool call with no call" in {
    remoteWhoami.execute(ujson.Obj()) shouldBe Right(ujson.Str("none"))
  }

  "The client" should "send the key in _meta, leaving the arguments as the tool's" in {
    val sent = new CopyOnWriteArrayList[JsonRpcRequest]()
    val transport = new MCPTransportImpl {
      override val name: String = "recording"
      override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
        sent.add(request): Unit
        val result = request.method match {
          case "initialize" => ujson.Obj("protocolVersion" -> "2025-06-18")
          case "tools/list" =>
            ujson.Obj(
              "tools" -> ujson.Arr(ujson.Obj("name" -> "t", "description" -> "t", "inputSchema" -> ujson.Obj()))
            )
          case _ => ujson.Obj("content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "ok")))
        }
        Right(JsonRpcResponse(id = request.id, result = Some(result)))
      }
      override def sendNotification(notification: JsonRpcNotification): Result[Unit] = Right(())
      override def close(): Unit                                                     = ()
    }
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("rec", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(transport)
    val tool = client.getTools().fold(e => fail(e.toString), identity).head
    tool.execute(ujson.Obj("a" -> 1), call)
    tool.execute(ujson.Obj("a" -> 1))
    val calls = sent.asScala.filter(_.method == "tools/call").map(_.params.get)
    calls.head("_meta") shouldBe ujson.Obj("llm4s.org/idempotencyKey" -> "key-1", "llm4s.org/toolCallId" -> "call_1")
    calls.head("arguments") shouldBe ujson.Obj("a" -> 1)
    calls(1).obj.contains("_meta") shouldBe false
  }

  "MCPToolCallMeta.fromParams" should "need an idempotency key, and fall back to the request id for the call id" in {
    def params(meta: ujson.Obj) = Some(ujson.Obj("name" -> "t", "_meta" -> meta))
    MCPToolCallMeta.fromParams(params(ujson.Obj("llm4s.org/idempotencyKey" -> "k")), "7") shouldBe
      Some(ToolCallInfo("7", "k"))
    MCPToolCallMeta.fromParams(params(ujson.Obj("llm4s.org/toolCallId" -> "c")), "7") shouldBe None
    MCPToolCallMeta.fromParams(params(ujson.Obj("llm4s.org/idempotencyKey" -> "")), "7") shouldBe None
    MCPToolCallMeta.fromParams(Some(ujson.Obj("name" -> "t")), "7") shouldBe None
    MCPToolCallMeta.fromParams(None, "7") shouldBe None
  }
}
