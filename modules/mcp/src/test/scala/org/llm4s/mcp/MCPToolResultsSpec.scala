package org.llm4s.mcp

import org.llm4s.error.SimpleError
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * What a `tools/call` result and a `tools/list` failure mean to the client and the registry: text stays text,
 * a structured result stays structured, request ids never repeat, and a failed listing is a `Left` that does
 * not make a server's known tools disappear.
 */
class MCPToolResultsSpec extends AnyFlatSpec with Matchers {

  private class Recording(callResult: ujson.Value) extends MCPTransportImpl {
    override val name: String = "recording"
    val ids                   = new CopyOnWriteArrayList[String]()

    override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
      ids.add(request.id): Unit
      val result: ujson.Value = request.method match {
        case "initialize" => ujson.Obj("protocolVersion" -> "2025-06-18")
        case "tools/list" =>
          ujson.Obj(
            "tools" -> ujson.Arr(
              ujson.Obj(
                "name"        -> "probe",
                "description" -> "probe",
                "inputSchema" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
              )
            )
          )
        case _ => callResult
      }
      Right(JsonRpcResponse(id = request.id, result = Some(result)))
    }
    override def sendNotification(notification: JsonRpcNotification): Result[Unit] = Right(())
    override def close(): Unit                                                     = ()
  }

  private def clientOver(transport: MCPTransportImpl): MCPClientImpl = {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("rec", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(transport)
    client
  }

  private def call(callResult: ujson.Value) = {
    val client = clientOver(new Recording(callResult))
    client.getTools().getOrElse(fail("listing failed")).head.execute(ujson.Obj())
  }

  private def text(s: String): ujson.Value = ujson.Arr(ujson.Obj("type" -> "text", "text" -> s))

  "A tool result" should "keep text that parses as JSON as text" in {
    call(ujson.Obj("content" -> text("24"))) shouldBe Right(ujson.Str("24"))
    call(ujson.Obj("content" -> text("null"))) shouldBe Right(ujson.Str("null"))
    call(ujson.Obj("content" -> text("""{"a":1}"""))) shouldBe Right(ujson.Str("""{"a":1}"""))
  }

  it should "be the structured content when the server sends it" in {
    call(ujson.Obj("content" -> text("""{"a":1}"""), "structuredContent" -> ujson.Obj("a" -> 1))) shouldBe
      Right(ujson.Obj("a" -> 1))
    call(ujson.Obj("content" -> text("7"), "structuredContent" -> ujson.Num(7))) shouldBe Right(ujson.Num(7))
  }

  it should "fall back to the text when the structured content is null" in {
    call(ujson.Obj("content" -> text("hi"), "structuredContent" -> ujson.Null)) shouldBe Right(ujson.Str("hi"))
  }

  it should "ignore structured content on an error result" in {
    val result = call(ujson.Obj("content" -> text("bad"), "isError" -> true, "structuredContent" -> ujson.Num(1)))
    result.left.toOption.map(_.getMessage).getOrElse("") should include("bad")
  }

  "The client's request ids" should "never repeat across handshake, listing and calls" in {
    val transport = new Recording(ujson.Obj("content" -> text("ok")))
    val client    = clientOver(transport)
    val tool      = client.getTools().getOrElse(fail("listing failed")).head
    (1 to 5).foreach(_ => tool.execute(ujson.Obj()))
    client.getTools()

    val ids = transport.ids.asScala.toSeq
    ids.size should be >= 7
    ids.distinct shouldBe ids
    (ids should not).contain("")
  }

  private class StubClient(var tools: Result[Seq[ToolFunction[?, ?]]]) extends MCPClient {
    override def initialize(): Result[Unit]                  = Right(())
    override def getTools(): Result[Seq[ToolFunction[?, ?]]] = tools
    override def close(): Unit                               = ()
  }

  "MCPToolRegistry" should "keep a server's last known tools when a refresh fails" in {
    val tool = new ToolFunction[ujson.Value, ujson.Value](
      "probe",
      "probe",
      org.llm4s.toolapi.ObjectSchema[ujson.Value]("p", Seq.empty, additionalProperties = false),
      _ => Right(ujson.Null)
    )
    val stub = new StubClient(Right(Seq(tool)))
    val registry = new MCPToolRegistry(
      Seq(MCPServerConfig.streamableHTTP("stub", "http://127.0.0.1:1/mcp", 5.seconds)),
      cacheTTL = 0.millis,
      initializeOnStartup = false
    ) {
      override private[mcp] def createMCPClient(server: MCPServerConfig): MCPClient = stub
    }
    registry.getAllTools.map(_.name) shouldBe Seq("probe")

    stub.tools = Left(SimpleError("server down"))
    registry.getAllTools.map(_.name) shouldBe Seq("probe")

    stub.tools = Right(Seq.empty)
    registry.getAllTools shouldBe empty
  }
}
