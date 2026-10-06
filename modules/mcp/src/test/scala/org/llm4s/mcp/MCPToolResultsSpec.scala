package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * What a `tools/call` result and a `tools/list` failure mean to the client and the registry: text stays text,
 * a structured result stays structured, request ids never repeat, and a failed listing is a `Left`, a malformed
 * tool is skipped, and a server whose refresh failed advertises no tool it can no longer call.
 */
class MCPToolResultsSpec extends AnyFlatSpec with Matchers {

  private def probe: ujson.Value = ujson.Obj(
    "name"        -> "probe",
    "description" -> "probe",
    "inputSchema" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
  )

  private class Recording(callResult: ujson.Value, listing: ujson.Value = ujson.Obj("tools" -> ujson.Arr(probe)))
      extends MCPTransportImpl {
    override val name: String = "recording"
    val ids                   = new CopyOnWriteArrayList[String]()

    override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
      ids.add(request.id): Unit
      val result: ujson.Value = request.method match {
        case "initialize" => ujson.Obj("protocolVersion" -> "2025-06-18")
        case "tools/list" => listing
        case _            => callResult
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

  it should "be the structured content when the server sends no content at all" in {
    call(ujson.Obj("structuredContent" -> ujson.Obj("a" -> 1))) shouldBe Right(ujson.Obj("a" -> 1))
  }

  it should "fail with a parse error when it carries neither content nor structured content" in {
    call(ujson.Obj()).left.toOption.map(_.getMessage).getOrElse("") should include("Failed to parse tool result")
  }

  "A tool listing" should "skip a malformed tool and return the others" in {
    val broken  = ujson.Obj("name" -> "broken") // no description, no inputSchema
    val listing = ujson.Obj("tools" -> ujson.Arr(broken, probe, ujson.Str("not an object")))
    val client  = clientOver(new Recording(ujson.Obj("content" -> text("ok")), listing))

    client.getTools().map(_.map(_.name)) shouldBe Right(Seq("probe"))
  }

  it should "give the hints of the tools it kept, and none for one it skipped" in {
    val annotated = ujson.Obj(
      "name"        -> "reader",
      "description" -> "reader",
      "inputSchema" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj()),
      "annotations" -> ujson.Obj("readOnlyHint" -> true)
    )
    val listing =
      ujson.Obj("tools" -> ujson.Arr(ujson.Obj("name" -> "broken", "annotations" -> ujson.Obj()), annotated))
    val config =
      MCPServerConfig.streamableHTTP("rec", "http://127.0.0.1:1/mcp", 5.seconds).copy(trustAnnotations = true)
    val client = new MCPClientImpl(config)
    client.transport = Some(new Recording(ujson.Obj(), listing))

    client.getTools().map(_.map(_.name)) shouldBe Right(Seq("reader"))
    client.getToolHints().keySet shouldBe Set("reader")
  }

  it should "fail, not look like an empty server, when none of its entries can be read" in {
    val listing = ujson.Obj("tools" -> ujson.Arr(ujson.Obj("name" -> "a"), ujson.Obj("name" -> "b")))
    val client  = clientOver(new Recording(ujson.Obj(), listing))

    client.getTools().isLeft shouldBe true
    client.getToolHints() shouldBe empty
  }

  it should "still fail as a whole when the listing itself cannot be read" in {
    val client = clientOver(new Recording(ujson.Obj(), ujson.Obj("tools" -> ujson.Str("not an array"))))

    client.getTools().isLeft shouldBe true
    client.getToolHints() shouldBe empty
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
    var closes                                               = 0
    override def initialize(): Result[Unit]                  = Right(())
    override def getTools(): Result[Seq[ToolFunction[?, ?]]] = tools
    override def close(): Unit                               = closes += 1
  }

  private def probeTool = new ToolFunction[ujson.Value, ujson.Value](
    "probe",
    "probe",
    org.llm4s.toolapi.ObjectSchema[ujson.Value]("p", Seq.empty, additionalProperties = false),
    _ => Right(ujson.Null)
  )

  private def registryOver(stub: StubClient) =
    new MCPToolRegistry(
      Seq(MCPServerConfig.streamableHTTP("stub", "http://127.0.0.1:1/mcp", 5.seconds)),
      cacheTTL = 0.millis,
      initializeOnStartup = false
    ) {
      override private[mcp] def createMCPClient(server: MCPServerConfig): MCPClient = stub
    }

  "MCPToolRegistry" should "advertise no tool of a server whose refresh failed, and the tools again once it recovers" in {
    val stub     = new StubClient(Right(Seq(probeTool)))
    val registry = registryOver(stub)
    registry.getAllTools.map(_.name) shouldBe Seq("probe")

    // A failed refresh closes the client its tools call through, so those tools can no longer be called:
    // none is advertised, rather than one the model would call and see fail.
    stub.tools = Left(SimpleError("server down"))
    registry.getAllTools shouldBe empty
    stub.closes shouldBe 1

    stub.tools = Right(Seq(probeTool))
    registry.getAllTools.map(_.name) shouldBe Seq("probe")

    stub.tools = Right(Seq.empty)
    registry.getAllTools shouldBe empty
  }

  it should "keep a server's tools when the refresh was only cancelled" in {
    val stub     = new StubClient(Right(Seq(probeTool)))
    val registry = registryOver(stub)
    registry.getAllTools.map(_.name) shouldBe Seq("probe")

    stub.tools = Left(CancelledError("refresh"))
    registry.getAllTools.map(_.name) shouldBe Seq("probe")
    stub.closes shouldBe 0
  }
}
