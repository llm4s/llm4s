package org.llm4s.mcp

import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.toolapi.{ ObjectSchema, SafeParameterExtractor, ToolFunction, ToolHints }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * `ToolHints` are read from the annotations an MCP server attaches to its tools (MCP specification, "Tool
 * Annotations"): the typed model, the client that records them while it discovers tools, and the registry
 * that answers by tool name.
 */
class MCPToolHintsSpec extends AnyFlatSpec with Matchers {

  private def toolJson(name: String, annotations: Option[ujson.Value]): ujson.Value = {
    val base = ujson.Obj(
      "name"        -> name,
      "description" -> s"the $name tool",
      "inputSchema" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
    )
    annotations.foreach(a => base("annotations") = a)
    base
  }

  private val advertised: ujson.Value = ujson.Arr(
    toolJson("reader", Some(ujson.Obj("readOnlyHint" -> true, "openWorldHint" -> false))),
    toolJson("writer", Some(ujson.Obj("destructiveHint" -> false, "idempotentHint" -> true))),
    toolJson("plain", None)
  )

  private val expected = Map(
    "reader" -> ToolHints(readOnly = true, openWorld = false),
    "writer" -> ToolHints(destructive = false, idempotent = true),
    "plain"  -> ToolHints.default
  )

  private def annotations(json: String): MCPToolAnnotations = MCPToolAnnotations.fromJson(ujson.read(json))

  // ---- the annotations model

  "MCPToolAnnotations" should "read every hint and the title" in {
    annotations(
      """{"title":"Reader","readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}"""
    ) shouldBe MCPToolAnnotations(
      title = Some("Reader"),
      readOnlyHint = Some(true),
      destructiveHint = Some(false),
      idempotentHint = Some(true),
      openWorldHint = Some(false)
    )
  }

  it should "map each hint to its ToolHints field, and only that one" in {
    annotations("""{"readOnlyHint":true}""").toToolHints shouldBe ToolHints(readOnly = true)
    annotations("""{"destructiveHint":false}""").toToolHints shouldBe ToolHints(destructive = false)
    annotations("""{"idempotentHint":true}""").toToolHints shouldBe ToolHints(idempotent = true)
    annotations("""{"openWorldHint":false}""").toToolHints shouldBe ToolHints(openWorld = false)
  }

  it should "give the specification's conservative defaults for a hint the server left out" in {
    annotations("{}").toToolHints shouldBe ToolHints.default
    ToolHints.default shouldBe ToolHints(readOnly = false, destructive = true, idempotent = false, openWorld = true)
  }

  it should "treat annotations that are missing, null or not an object as absent" in {
    MCPToolAnnotations.fromJson(ujson.Null) shouldBe MCPToolAnnotations()
    MCPToolAnnotations.fromJson(ujson.Str("readOnly")) shouldBe MCPToolAnnotations()
    MCPToolAnnotations.fromJson(ujson.Arr(ujson.Bool(true))).toToolHints shouldBe ToolHints.default
  }

  it should "ignore an unknown key and a hint of the wrong type, keeping the hints that are valid" in {
    val read = annotations("""{"readOnlyHint":"yes","idempotentHint":true,"futureHint":true,"title":42}""")

    read shouldBe MCPToolAnnotations(idempotentHint = Some(true))
    read.toToolHints shouldBe ToolHints(idempotent = true)
  }

  it should "round-trip through MCPTool's JSON, and leave a tool without annotations without them" in {
    val tool = MCPTool(
      "lookup",
      "Looks something up",
      ujson.Obj("type" -> "object"),
      Some(MCPToolAnnotations(readOnlyHint = Some(true)))
    )

    ujson.read(write(tool))("annotations") shouldBe ujson.Obj("readOnlyHint" -> true)
    read[MCPTool](write(tool)) shouldBe tool
    read[MCPTool]("""{"name":"n","description":"d","inputSchema":{"type":"object"}}""").annotations shouldBe None
  }

  // ---- the client

  private class ScriptedTransport(tools: ujson.Value) extends MCPTransportImpl {
    override val name: String = "scripted"

    override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
      val result: ujson.Value = request.method match {
        case "initialize" => ujson.Obj("protocolVersion" -> "2025-06-18")
        case _            => ujson.Obj("tools" -> tools)
      }
      Right(JsonRpcResponse(id = request.id, result = Some(result)))
    }

    override def sendNotification(notification: JsonRpcNotification): Result[Unit] = Right(())
    override def close(): Unit                                                     = ()
  }

  "MCPClientImpl" should "record the hints each advertised tool declares, by tool name" in {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("scripted", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(new ScriptedTransport(advertised))

    client.getToolHints() shouldBe empty // nothing is known before the tools are listed
    client.getTools().map(_.map(_.name).toSet) shouldBe Right(Set("reader", "writer", "plain"))
    client.getToolHints() shouldBe expected
  }

  it should "keep the hints of the last successful listing when a later one cannot be read" in {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("scripted", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(new ScriptedTransport(advertised))
    client.getTools()

    client.transport = Some(new ScriptedTransport(ujson.Arr(ujson.Obj("description" -> "no name"))))
    client.getTools() shouldBe Right(Seq.empty) // an unreadable listing is logged and swallowed, as before

    client.getToolHints() shouldBe expected
  }

  // ---- the registry

  /** A minimal MCP server over Streamable HTTP: the handshake, and `advertised` for tools/list. */
  private def mcpServer(exchange: HttpExchange): Unit = {
    val body   = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
    val method = body("method").str
    if (!body.obj.contains("id")) { // a notification: accepted, no body
      exchange.sendResponseHeaders(202, -1)
      exchange.close()
    } else {
      val result: ujson.Value = method match {
        case "initialize" =>
          ujson.Obj(
            "protocolVersion" -> "2025-06-18",
            "capabilities"    -> ujson.Obj(),
            "serverInfo"      -> ujson.Obj("name" -> "hints", "version" -> "1")
          )
        case _ => ujson.Obj("tools" -> advertised)
      }
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        200,
        ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> body("id"), "result" -> result))
      )
    }
  }

  private def localTool(name: String): ToolFunction[?, ?] =
    new ToolFunction[Map[String, Any], String](
      name,
      "a local tool",
      ObjectSchema[Map[String, Any]]("no parameters", Seq.empty),
      (_: SafeParameterExtractor) => Right("local")
    )

  "MCPToolRegistry" should "answer with the hints a server declares, once it has discovered its tools" in
    LocalProviderTestServer.withServer("/")(mcpServer) { url =>
      val registry =
        new MCPToolRegistry(
          Seq(MCPServerConfig.streamableHTTP("hints", url, 10.seconds)),
          initializeOnStartup = false
        )
      try {
        registry.toolHints("reader") shouldBe None // not discovered yet

        registry.getAllTools.map(_.name).toSet shouldBe Set("reader", "writer", "plain")

        expected.foreach { case (name, hints) => withClue(name)(registry.toolHints(name) shouldBe Some(hints)) }
        registry.toolHints("unknown") shouldBe None
      } finally registry.close()
    }

  it should "give no hints for an MCP tool that a local tool of the same name shadows" in
    LocalProviderTestServer.withServer("/")(mcpServer) { url =>
      val registry = new MCPToolRegistry(
        Seq(MCPServerConfig.streamableHTTP("hints", url, 10.seconds)),
        localTools = Seq(localTool("reader")),
        initializeOnStartup = false
      )
      try {
        registry.getAllTools: Unit
        registry.toolHints("reader") shouldBe None
        registry.toolHints("writer") shouldBe Some(expected("writer"))
      } finally registry.close()
    }
}
