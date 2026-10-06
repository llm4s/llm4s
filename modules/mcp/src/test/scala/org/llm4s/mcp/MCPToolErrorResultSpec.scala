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

    override def sendRequest(request: JsonRpcRequest): org.llm4s.types.Result[JsonRpcResponse] = {
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

    override def sendNotification(notification: JsonRpcNotification): org.llm4s.types.Result[Unit] = Right(())
    override def close(): Unit                                                                     = ()
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

  private def failure(callResult: ujson.Value): String = {
    val result = execute(callResult)
    result.isLeft shouldBe true
    result.left.toOption.map(_.toString).getOrElse("")
  }

  it should "be reported as a failure carrying the server's text when isError is true" in {
    failure(ujson.Obj("content" -> content("disk on fire"), "isError" -> true)) should include(
      "Tool call failed: disk on fire"
    )
  }

  it should "keep non-JSON-looking and JSON-looking error text verbatim" in {
    failure(ujson.Obj("content" -> content("""{"code":7}"""), "isError" -> true)) should include(
      """Tool call failed: {"code":7}"""
    )
  }

  it should "use only the first text part when an error carries several parts" in {
    val parts = ujson.Arr(
      ujson.Obj("type" -> "text", "text" -> "first"),
      ujson.Obj("type" -> "text", "text" -> "second")
    )
    val msg = failure(ujson.Obj("content" -> parts, "isError" -> true))
    msg should include("Tool call failed: first")
    (msg should not).include("second")
  }

  it should "report a generic failure when an error has empty content" in {
    failure(ujson.Obj("content" -> ujson.Arr(), "isError" -> true)) should include(
      "Tool call failed: server reported an error"
    )
  }

  it should "report a generic failure when an error has no content field" in {
    failure(ujson.Obj("isError" -> true)) should include("Tool call failed: server reported an error")
  }

  it should "report a generic failure when an error has only a non-text part" in {
    val image = ujson.Arr(ujson.Obj("type" -> "image", "data" -> "AAAA", "mimeType" -> "image/png"))
    failure(ujson.Obj("content" -> image, "isError" -> true)) should include(
      "Tool call failed: server reported an error"
    )
  }

  it should "not treat a non-boolean isError as a failure" in {
    execute(ujson.Obj("content" -> content("fine"), "isError" -> "true")) shouldBe Right(ujson.Str("fine"))
    execute(ujson.Obj("content" -> content("fine"), "isError" -> 1)) shouldBe Right(ujson.Str("fine"))
  }

  it should "still parse JSON text and report empty content as a result when isError is false" in {
    execute(ujson.Obj("content" -> content("""{"a":1}"""), "isError" -> false)) shouldBe Right(ujson.Obj("a" -> 1))
    execute(ujson.Obj("content" -> ujson.Arr(), "isError" -> false)) shouldBe
      Right(ujson.Obj("result" -> "No content returned"))
  }

  it should "fail with a parse error, not a tool error, for malformed non-error results" in {
    failure(ujson.Obj("isError" -> false)) should include("Failed to parse tool result")
  }
}
