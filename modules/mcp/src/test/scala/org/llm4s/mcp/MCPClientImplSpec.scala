package org.llm4s.mcp

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.toolapi.ToolFunction
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class MCPClientImplSpec extends AnyFlatSpec with Matchers with MockFactory with EitherValues {

  override def withFixture(test: NoArgTest): Outcome = {
    val clientLogger = LoggerFactory.getLogger("org.llm4s.mcp.MCPClientImpl").asInstanceOf[LBLogger]
    val stdioLogger  = LoggerFactory.getLogger("org.llm4s.mcp.StdioTransportImpl").asInstanceOf[LBLogger]

    val previousClientLevel = clientLogger.getLevel
    val previousStdioLevel  = stdioLogger.getLevel

    clientLogger.setLevel(Level.OFF)
    stdioLogger.setLevel(Level.OFF)

    try super.withFixture(test)
    finally {
      clientLogger.setLevel(previousClientLevel)
      stdioLogger.setLevel(previousStdioLevel)
    }
  }

  // Test fixtures
  val config = MCPServerConfig.stdio(
    name = "test-server",
    command = Seq("test-command"),
    timeout = 30.seconds
  )

  "MCPClientImpl.getTools" should "return tools when initialization succeeds" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    // Initialize response
    val initResponse = JsonRpcResponse(
      jsonrpc = "2.0",
      id = "1",
      result = Some(ujson.Obj("protocolVersion" -> "2025-06-18"))
    )

    // Tools response
    val toolsResponse = JsonRpcResponse(
      jsonrpc = "2.0",
      id = "2",
      result = Some(
        ujson.Obj(
          "tools" -> ujson.Arr(
            ujson.Obj(
              "name"        -> "test-tool",
              "description" -> "A test tool",
              "inputSchema" -> ujson.Obj(
                "type"       -> "object",
                "properties" -> ujson.Obj()
              )
            )
          )
        )
      )
    )

    // Setup expectations
    (mockTransport.sendRequest _)
      .expects(where((req: JsonRpcRequest) => req.method == "initialize"))
      .returning(Right(initResponse))

    (mockTransport.sendNotification _)
      .expects(where((notif: JsonRpcNotification) => notif.method == "notifications/initialized"))
      .returning(Right(()))

    val request = JsonRpcRequest("2.0", "2", "tools/list", None)
    (mockTransport.sendRequest _)
      .expects(request)
      .returning(Right(toolsResponse))

    client.transport = Some(mockTransport)
    // Act
    val result: org.llm4s.types.Result[Seq[ToolFunction[?, ?]]] = client.getTools()

    // Assert
    result.map(_.map(_.name)) shouldBe Right(Seq("test-tool"))
  }

  it should "fail when initialization fails" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    (mockTransport.sendRequest _)
      .expects(*)
      .returning(Left(org.llm4s.error.SimpleError("Initialization failed")))

    client.transport = Some(mockTransport)

    // Act
    val result = client.getTools()

    // Assert
    result.isLeft shouldBe true
  }

  it should "fail when no transport is available" in {
    // Arrange
    val client = new MCPClientImpl(config)
    client.transport = None

    // Act
    val result = client.getTools()

    // Assert
    result.isLeft shouldBe true
  }

  it should "fail on an invalid tool listing" in {
    // Arrange
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]

    val p = JsonRpcRequest(
      "2.0",
      "1",
      "initialize",
      Some(ujson.Value("""
          |{"protocolVersion":"2025-06-18","capabilities":{"tools":{},"roots":{"listChanged":false},"sampling":{}},"clientInfo":{"name":"llm4s-mcp","version":"1.0.0"}}
          |""".stripMargin))
    )

    // Mock successful initialization but invalid tools response
    (mockTransport.sendRequest _)
      .expects(p)
      .returning(
        Right(
          JsonRpcResponse(
            "2.0",
            "1",
            Some(ujson.Obj("protocolVersion" -> "2025-06-18"))
          )
        )
      )

    client.transport = Some(mockTransport)
    // Act
    val result = client.getTools()

    // Assert
    result.isLeft shouldBe true
  }

  // A property's schema is converted as it is, never to a string it is not (#2814)

  private def toolWithProperties(properties: ujson.Obj, required: Seq[String]): ToolFunction[?, ?] = {
    val client        = new MCPClientImpl(config)
    val mockTransport = mock[MCPTransportImpl]
    val initResponse  = JsonRpcResponse("2.0", "1", Some(ujson.Obj("protocolVersion" -> "2025-06-18")))
    val toolsResponse = JsonRpcResponse(
      "2.0",
      "2",
      Some(
        ujson.Obj(
          "tools" -> ujson.Arr(
            ujson.Obj(
              "name"        -> "shaped",
              "description" -> "A tool with structured arguments",
              "inputSchema" -> ujson.Obj(
                "type"       -> "object",
                "properties" -> properties,
                "required"   -> ujson.Arr.from(required.map(ujson.Str(_)))
              )
            )
          )
        )
      )
    )
    (mockTransport.sendRequest _)
      .expects(where((req: JsonRpcRequest) => req.method == "initialize"))
      .returning(Right(initResponse))
    (mockTransport.sendNotification _)
      .expects(where((notif: JsonRpcNotification) => notif.method == "notifications/initialized"))
      .returning(Right(()))
    (mockTransport.sendRequest _)
      .expects(JsonRpcRequest("2.0", "2", "tools/list", None))
      .returning(Right(toolsResponse))
    client.transport = Some(mockTransport)
    client.getTools().toOption.get.head
  }

  private def propertiesOf(tool: ToolFunction[?, ?]): ujson.Value =
    tool.schema.toJsonSchema(strict = false)("properties")

  "MCPClientImpl" should "describe a nested object property as an object with its own properties" in {
    val tool = toolWithProperties(
      ujson.Obj(
        "filter" -> ujson.Obj(
          "type"        -> "object",
          "description" -> "What to match",
          "properties"  -> ujson.Obj("status" -> ujson.Obj("type" -> "string", "enum" -> ujson.Arr("open", "done"))),
          "required"    -> ujson.Arr("status")
        )
      ),
      Seq("filter")
    )

    val filter = propertiesOf(tool)("filter")
    filter("type").str shouldBe "object"
    filter("description").str shouldBe "What to match"
    filter("properties")("status")("enum").arr.map(_.str).toSeq shouldBe Seq("open", "done")
    filter("required").arr.map(_.str).toSeq shouldBe Seq("status")
  }

  it should "describe an anyOf property as a union of its alternatives" in {
    val tool = toolWithProperties(
      ujson.Obj(
        "id" -> ujson.Obj(
          "description" -> "A number or a name",
          "anyOf"       -> ujson.Arr(ujson.Obj("type" -> "integer"), ujson.Obj("type" -> "string"))
        )
      ),
      Seq("id")
    )

    val id = propertiesOf(tool)("id")
    id("description").str shouldBe "A number or a name"
    id("anyOf").arr.map(_("type").str).toSeq shouldBe Seq("integer", "string")
    id.obj.contains("type") shouldBe false
  }

  it should "describe a type that allows null as nullable, and array items by their own schema" in {
    val tool = toolWithProperties(
      ujson.Obj(
        "note" -> ujson.Obj("type" -> ujson.Arr("string", "null")),
        "rows" -> ujson.Obj(
          "type"  -> "array",
          "items" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj("n" -> ujson.Obj("type" -> "integer")))
        )
      ),
      Seq.empty
    )

    val properties = propertiesOf(tool)
    properties("note")("type") shouldBe ujson.Arr("string", "null")
    properties("rows")("items")("type").str shouldBe "object"
    properties("rows")("items")("properties")("n")("type").str shouldBe "integer"
  }

  it should "keep a schema the model has no case for as the server wrote it" in {
    val reference = ujson.Obj("$ref" -> "#/$defs/Address")
    val freeForm  = ujson.Obj("type" -> "object", "description" -> "Any headers", "additionalProperties" -> true)
    val tool      = toolWithProperties(ujson.Obj("address" -> reference, "headers" -> freeForm), Seq.empty)

    propertiesOf(tool)("address") shouldBe reference
    propertiesOf(tool)("headers") shouldBe freeForm
  }
}
