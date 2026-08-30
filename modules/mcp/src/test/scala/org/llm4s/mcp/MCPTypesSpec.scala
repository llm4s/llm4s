package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * Tests for MCP protocol types and JSON serialization
 */
class MCPTypesSpec extends AnyFlatSpec with Matchers {

  // ============ JsonRpcRequest ============

  "JsonRpcRequest" should "serialize to JSON correctly" in {
    val request = JsonRpcRequest("2.0", "req-1", "initialize", Some(ujson.Obj("key" -> "value")))
    val json    = write(request)
    val parsed  = ujson.read(json)

    parsed("jsonrpc").str shouldBe "2.0"
    parsed("id").str shouldBe "req-1"
    parsed("method").str shouldBe "initialize"
    parsed("params")("key").str shouldBe "value"
  }

  it should "serialize without params" in {
    val request = JsonRpcRequest("2.0", "req-2", "tools/list", None)
    val json    = write(request)
    val parsed  = ujson.read(json)

    parsed("jsonrpc").str shouldBe "2.0"
    parsed("method").str shouldBe "tools/list"
  }

  it should "deserialize from JSON" in {
    val json    = """{"jsonrpc":"2.0","id":"req-3","method":"test","params":{"x":1}}"""
    val request = read[JsonRpcRequest](json)

    request.jsonrpc shouldBe "2.0"
    request.id shouldBe "req-3"
    request.method shouldBe "test"
    request.params.get("x").num.toInt shouldBe 1
  }

  // ============ JsonRpcNotification ============

  "JsonRpcNotification" should "serialize to JSON correctly" in {
    val notification = JsonRpcNotification("2.0", "initialized", Some(ujson.Obj()))
    val json         = write(notification)
    val parsed       = ujson.read(json)

    parsed("jsonrpc").str shouldBe "2.0"
    parsed("method").str shouldBe "initialized"
  }

  it should "serialize without params" in {
    val notification = JsonRpcNotification("2.0", "ping", None)
    val json         = write(notification)
    val parsed       = ujson.read(json)

    parsed("jsonrpc").str shouldBe "2.0"
    parsed("method").str shouldBe "ping"
  }

  // ============ JsonRpcResponse ============

  "JsonRpcResponse" should "serialize successful response" in {
    val response = JsonRpcResponse("2.0", "req-1", Some(ujson.Obj("tools" -> ujson.Arr())), None)
    val json     = write(response)
    val parsed   = ujson.read(json)

    // Check the fields that are always present
    parsed("id").str shouldBe "req-1"
    parsed("result")("tools").arr shouldBe empty
  }

  it should "serialize error response" in {
    val error    = JsonRpcError(-32600, "Invalid Request", None)
    val response = JsonRpcResponse("2.0", "req-2", None, Some(error))
    val json     = write(response)
    val parsed   = ujson.read(json)

    parsed("error")("code").num.toInt shouldBe -32600
    parsed("error")("message").str shouldBe "Invalid Request"
  }

  it should "deserialize from JSON" in {
    val json     = """{"jsonrpc":"2.0","id":"req-3","result":{"value":42}}"""
    val response = read[JsonRpcResponse](json)

    response.id shouldBe "req-3"
    response.result.get("value").num.toInt shouldBe 42
    response.error shouldBe None
  }

  // ============ JsonRpcError ============

  "JsonRpcError" should "serialize with optional data" in {
    val error  = JsonRpcError(-32000, "Custom error", Some(ujson.Obj("detail" -> "extra info")))
    val json   = write(error)
    val parsed = ujson.read(json)

    parsed("code").num.toInt shouldBe -32000
    parsed("message").str shouldBe "Custom error"
    parsed("data")("detail").str shouldBe "extra info"
  }

  it should "serialize without data" in {
    val error  = JsonRpcError(-32602, "Invalid params", None)
    val json   = write(error)
    val parsed = ujson.read(json)

    parsed("code").num.toInt shouldBe -32602
    parsed("message").str shouldBe "Invalid params"
  }

  // ============ ClientInfo / ServerInfo ============

  "ClientInfo" should "serialize correctly" in {
    val info   = ClientInfo("llm4s", "1.0.0")
    val json   = write(info)
    val parsed = ujson.read(json)

    parsed("name").str shouldBe "llm4s"
    parsed("version").str shouldBe "1.0.0"
  }

  "ServerInfo" should "serialize correctly" in {
    val info   = ServerInfo("test-server", "2.0.0")
    val json   = write(info)
    val parsed = ujson.read(json)

    parsed("name").str shouldBe "test-server"
    parsed("version").str shouldBe "2.0.0"
  }

  // ============ MCPCapabilities ============

  "MCPCapabilities" should "have sensible defaults" in {
    val caps = MCPCapabilities()

    caps.tools shouldBe Some(ujson.Obj())
    caps.logging shouldBe None
    caps.prompts shouldBe None
    caps.resources shouldBe None
    caps.roots shouldBe Some(ujson.Obj("listChanged" -> ujson.Bool(false)))
    caps.sampling shouldBe Some(ujson.Obj())
  }

  it should "serialize to JSON" in {
    val caps = MCPCapabilities()
    val json = write(caps)

    // Check that capabilities structure is correct when deserialized back
    val deserCaps = read[MCPCapabilities](json)
    deserCaps.tools shouldBe Some(ujson.Obj())
    deserCaps.roots.flatMap(_.obj.get("listChanged")).map(_.bool) shouldBe Some(false)
  }

  // ============ InitializeRequest / InitializeResponse ============

  "InitializeRequest" should "serialize correctly" in {
    val request = InitializeRequest(
      protocolVersion = "2024-11-05",
      capabilities = MCPCapabilities(),
      clientInfo = ClientInfo("llm4s", "1.0.0")
    )
    val json   = write(request)
    val parsed = ujson.read(json)

    parsed("protocolVersion").str shouldBe "2024-11-05"
    parsed("clientInfo")("name").str shouldBe "llm4s"
  }

  "InitializeResponse" should "deserialize correctly" in {
    val json     = """{
      "protocolVersion": "2024-11-05",
      "capabilities": {"tools": {}},
      "serverInfo": {"name": "test", "version": "1.0"}
    }"""
    val response = read[InitializeResponse](json)

    response.protocolVersion shouldBe "2024-11-05"
    response.serverInfo.name shouldBe "test"
    response.serverInfo.version shouldBe "1.0"
  }

  // ============ MCPTool ============

  "MCPTool" should "serialize correctly" in {
    val tool = MCPTool(
      name = "calculator",
      description = "Performs calculations",
      inputSchema = ujson.Obj(
        "type"       -> "object",
        "properties" -> ujson.Obj("expression" -> ujson.Obj("type" -> "string"))
      )
    )
    val json   = write(tool)
    val parsed = ujson.read(json)

    parsed("name").str shouldBe "calculator"
    parsed("description").str shouldBe "Performs calculations"
    parsed("inputSchema")("type").str shouldBe "object"
  }

  it should "deserialize correctly" in {
    val json = """{"name":"test_tool","description":"A test tool","inputSchema":{"type":"object"}}"""
    val tool = read[MCPTool](json)

    tool.name shouldBe "test_tool"
    tool.description shouldBe "A test tool"
  }

  // ============ ToolsListResponse ============

  "ToolsListResponse" should "serialize list of tools" in {
    val response = ToolsListResponse(
      tools = Seq(
        MCPTool("tool1", "First tool", ujson.Obj("type" -> "object")),
        MCPTool("tool2", "Second tool", ujson.Obj("type" -> "object"))
      )
    )
    val json   = write(response)
    val parsed = ujson.read(json)

    parsed("tools").arr should have size 2
    parsed("tools")(0)("name").str shouldBe "tool1"
    parsed("tools")(1)("name").str shouldBe "tool2"
  }

  it should "deserialize empty list" in {
    val json     = """{"tools":[]}"""
    val response = read[ToolsListResponse](json)

    response.tools shouldBe empty
  }

  // ============ ToolsCallRequest / ToolsCallResponse ============

  "ToolsCallRequest" should "serialize with arguments" in {
    val request = ToolsCallRequest("calculator", Some(ujson.Obj("expression" -> "1+1")))
    val json    = write(request)
    val parsed  = ujson.read(json)

    parsed("name").str shouldBe "calculator"
    parsed("arguments")("expression").str shouldBe "1+1"
  }

  it should "serialize without arguments" in {
    val request = ToolsCallRequest("ping", None)
    val json    = write(request)
    val parsed  = ujson.read(json)

    parsed("name").str shouldBe "ping"
  }

  "ToolsCallResponse" should "serialize with content" in {
    val response = ToolsCallResponse(
      content = Seq(MCPContent("text", Some("Result: 2"), None, None)),
      isError = Some(false)
    )
    val json   = write(response)
    val parsed = ujson.read(json)

    parsed("content")(0)("type").str shouldBe "text"
    parsed("content")(0)("text").str shouldBe "Result: 2"
    parsed("isError").bool shouldBe false
  }

  it should "serialize error response" in {
    val response = ToolsCallResponse(
      content = Seq(MCPContent("text", Some("Error occurred"), None, None)),
      isError = Some(true)
    )
    val json   = write(response)
    val parsed = ujson.read(json)

    parsed("isError").bool shouldBe true
  }

  // ============ MCPContent ============

  "MCPContent" should "serialize text content" in {
    val content = MCPContent("text", Some("Hello world"), None, None)
    val json    = write(content)
    val parsed  = ujson.read(json)

    parsed("type").str shouldBe "text"
    parsed("text").str shouldBe "Hello world"
  }

  it should "serialize resource content" in {
    val content =
      MCPContent("resource", None, Some(ResourceReference("file:///path/to/file", Some("text/plain"))), None)
    val json   = write(content)
    val parsed = ujson.read(json)

    parsed("type").str shouldBe "resource"
    parsed("resource")("uri").str shouldBe "file:///path/to/file"
    parsed("resource")("type").str shouldBe "text/plain"
  }

  it should "serialize with annotations" in {
    val content = MCPContent("text", Some("Annotated"), None, Some(ujson.Obj("priority" -> "high")))
    val json    = write(content)
    val parsed  = ujson.read(json)

    parsed("annotations")("priority").str shouldBe "high"
  }

  // ============ ResourceReference ============

  "ResourceReference" should "serialize with type" in {
    val ref    = ResourceReference("https://example.com/resource", Some("application/json"))
    val json   = write(ref)
    val parsed = ujson.read(json)

    parsed("uri").str shouldBe "https://example.com/resource"
    parsed("type").str shouldBe "application/json"
  }

  it should "serialize without type" in {
    val ref    = ResourceReference("file:///local/file.txt", None)
    val json   = write(ref)
    val parsed = ujson.read(json)

    parsed("uri").str shouldBe "file:///local/file.txt"
  }

  // ============ MCPErrorCodes ============

  "MCPErrorCodes" should "define standard JSON-RPC error codes" in {
    MCPErrorCodes.PARSE_ERROR shouldBe -32700
    MCPErrorCodes.INVALID_REQUEST shouldBe -32600
    MCPErrorCodes.METHOD_NOT_FOUND shouldBe -32601
    MCPErrorCodes.INVALID_PARAMS shouldBe -32602
    MCPErrorCodes.INTERNAL_ERROR shouldBe -32603
  }

  it should "define MCP-specific error codes" in {
    MCPErrorCodes.INVALID_PROTOCOL_VERSION shouldBe -32000
    MCPErrorCodes.TOOL_NOT_FOUND shouldBe -32001
    MCPErrorCodes.TOOL_EXECUTION_ERROR shouldBe -32002
    MCPErrorCodes.SESSION_EXPIRED shouldBe -32003
    MCPErrorCodes.UNAUTHORIZED shouldBe -32004
    MCPErrorCodes.RESOURCE_NOT_FOUND shouldBe -32005
    MCPErrorCodes.TRANSPORT_ERROR shouldBe -32006
    MCPErrorCodes.TIMEOUT_ERROR shouldBe -32007
  }

  it should "return correct error messages" in {
    MCPErrorCodes.getErrorMessage(MCPErrorCodes.PARSE_ERROR) shouldBe "Parse error"
    MCPErrorCodes.getErrorMessage(MCPErrorCodes.TOOL_NOT_FOUND) shouldBe "Tool not found"
    MCPErrorCodes.getErrorMessage(-99999) should include("Unknown error")
  }

  it should "create transport error" in {
    val error = MCPErrorCodes.transportError("Connection failed")

    error.code shouldBe MCPErrorCodes.TRANSPORT_ERROR
    error.message shouldBe "Connection failed"
  }

  it should "create timeout error" in {
    val error = MCPErrorCodes.timeoutError("Request timed out")

    error.code shouldBe MCPErrorCodes.TIMEOUT_ERROR
    error.message shouldBe "Request timed out"
  }

  it should "create tool not found error" in {
    val error = MCPErrorCodes.toolNotFound("missing_tool")

    error.code shouldBe MCPErrorCodes.TOOL_NOT_FOUND
    error.message should include("missing_tool")
  }

  it should "create tool execution error" in {
    val error = MCPErrorCodes.toolExecutionError("calculator", "Division by zero")

    error.code shouldBe MCPErrorCodes.TOOL_EXECUTION_ERROR
    error.message should include("calculator")
    error.message should include("Division by zero")
  }
}
