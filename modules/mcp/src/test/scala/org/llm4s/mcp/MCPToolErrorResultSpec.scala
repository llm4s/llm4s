package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * MCP servers report tool-level failures as a successful JSON-RPC response whose result carries
 * `isError: true` and the error text in `content` (MCP specification, tools/call). The client must
 * surface such a result as a failed tool execution, not as a successful one.
 */
class MCPToolErrorResultSpec extends AnyFlatSpec with Matchers {

  private class ScriptedTransport(callResult: ujson.Value) extends MCPTransportImpl {
    override val name: String = "scripted"

    override def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse] = {
      val result: ujson.Value = request.method match {
        case "initialize" => ujson.Obj("protocolVersion" -> "2025-06-18")
        case "tools/list" =>
          ujson.Obj(
            "tools" -> ujson.Arr(
              ujson.Obj(
                "name"        -> "probe",
                "description" -> "probe tool",
                "inputSchema" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
              )
            )
          )
        case _ => callResult
      }
      Right(JsonRpcResponse(id = request.id, result = Some(result)))
    }

    override def sendNotification(notification: JsonRpcNotification): Either[String, Unit] = Right(())
    override def close(): Unit                                                             = ()
  }

  private def execute(callResult: ujson.Value): Either[org.llm4s.toolapi.ToolCallError, ujson.Value] = {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("scripted", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(new ScriptedTransport(callResult))
    val tool = client.getTools().getOrElse(Seq.empty).headOption.getOrElse(fail("probe tool not advertised"))
    tool.execute(ujson.Obj())
  }

  private def content(text: String): ujson.Value =
    ujson.Arr(ujson.Obj("type" -> "text", "text" -> text))

  "An MCP tool result" should "be returned when isError is false" in {
    execute(ujson.Obj("content" -> content("fine"), "isError" -> false)) shouldBe Right(ujson.Str("fine"))
  }

  it should "be returned when isError is absent" in {
    execute(ujson.Obj("content" -> content("fine"))) shouldBe Right(ujson.Str("fine"))
  }

  it should "be reported as a failure carrying the server's text when isError is true" in {
    val result = execute(ujson.Obj("content" -> content("disk on fire"), "isError" -> true))
    result.isLeft shouldBe true
    result.left.toOption.map(_.toString).getOrElse("") should include("disk on fire")
  }
}
