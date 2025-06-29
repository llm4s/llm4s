package org.llm4s.mcp

import upickle.default._

/**
 * JSON-RPC 2.0 request structure for MCP protocol communication.
 * Used to send requests to MCP servers.
 *
 * @param jsonrpc Protocol version, always "2.0"
 * @param id Unique identifier for this request
 * @param method The method name to invoke on the server
 * @param params Optional parameters for the method
 */
case class JsonRpcRequest(
  jsonrpc: String = "2.0",
  id: String,
  method: String,
  params: Option[ujson.Value] = None
)

/**
 * JSON-RPC 2.0 response structure returned by MCP servers.
 * Contains either a result or error, never both.
 *
 * @param jsonrpc Protocol version, always "2.0"
 * @param id Identifier matching the original request
 * @param result Optional result data when successful
 * @param error Optional error information when failed
 */
case class JsonRpcResponse(
  jsonrpc: String = "2.0",
  id: String,
  result: Option[ujson.Value] = None,
  error: Option[JsonRpcError] = None
)

/**
 * JSON-RPC 2.0 error structure used in failed responses.
 *
 * @param code Numeric error code
 * @param message Human-readable error description
 * @param data Optional additional error data
 */
case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[ujson.Value] = None
)

/**
 * Client information sent during MCP protocol initialization.
 * Identifies the client application to the server.
 *
 * @param name Name of the client application
 * @param version Version of the client application
 */
case class ClientInfo(
  name: String,
  version: String
)

/**
 * Server information returned during MCP protocol initialization.
 * Identifies the server application to the client.
 *
 * @param name Name of the server application
 * @param version Version of the server application
 */
case class ServerInfo(
  name: String,
  version: String
)

/**
 * Capabilities structure defining what features are supported.
 * Used by both client and server during initialization handshake.
 *
 * @param tools Tool execution capabilities
 * @param logging Logging capabilities
 * @param prompts Prompt management capabilities
 * @param resources Resource access capabilities
 */
case class MCPCapabilities(
  tools: Option[ujson.Value] = Some(ujson.Obj()),
  logging: Option[ujson.Value] = None,
  prompts: Option[ujson.Value] = None,
  resources: Option[ujson.Value] = None
)

/**
 * Initialization request sent by client to establish MCP connection.
 * First message in the MCP protocol handshake.
 *
 * @param protocolVersion Version of MCP protocol to use
 * @param capabilities Client capabilities being advertised
 * @param clientInfo Information about the client application
 */
case class InitializeRequest(
  protocolVersion: String,
  capabilities: MCPCapabilities,
  clientInfo: ClientInfo
)

/**
 * Initialization response returned by server during MCP handshake.
 * Completes the protocol negotiation process.
 *
 * @param protocolVersion Version of MCP protocol server will use
 * @param capabilities Server capabilities being advertised
 * @param serverInfo Information about the server application
 */
case class InitializeResponse(
  protocolVersion: String,
  capabilities: MCPCapabilities,
  serverInfo: ServerInfo
)

/**
 * Tool definition structure as provided by MCP servers.
 * Describes a tool that can be executed remotely.
 *
 * @param name Unique tool identifier
 * @param description Human-readable tool description
 * @param inputSchema JSON Schema defining expected parameters
 */
case class MCPTool(
  name: String,
  description: String,
  inputSchema: ujson.Value
)

/**
 * Response from tools/list request containing available tools.
 *
 * @param tools Sequence of tool definitions available on the server
 */
case class ToolsListResponse(
  tools: Seq[MCPTool]
)

/**
 * Request structure for executing a tool via tools/call.
 *
 * @param name Name of the tool to execute
 * @param arguments Optional arguments to pass to the tool
 */
case class ToolsCallRequest(
  name: String,
  arguments: Option[ujson.Value] = None
)

/**
 * Response from tool execution containing the results.
 *
 * @param content Array of content items returned by the tool
 * @param isError Optional flag indicating if this represents an error
 */
case class ToolsCallResponse(
  content: Seq[MCPContent],
  isError: Option[Boolean] = None
)

/**
 * Individual content item within a tool response.
 *
 * @param `type` Content type, typically "text"
 * @param text The actual content text
 */
case class MCPContent(
  `type`: String,
  text: String
)

// Serialization support for JSON marshalling/unmarshalling
object JsonRpcRequest {
  implicit val rw: ReadWriter[JsonRpcRequest] = macroRW
}

object JsonRpcResponse {
  implicit val rw: ReadWriter[JsonRpcResponse] = macroRW
}

object JsonRpcError {
  implicit val rw: ReadWriter[JsonRpcError] = macroRW
}

object ClientInfo {
  implicit val rw: ReadWriter[ClientInfo] = macroRW
}

object ServerInfo {
  implicit val rw: ReadWriter[ServerInfo] = macroRW
}

object MCPCapabilities {
  implicit val rw: ReadWriter[MCPCapabilities] = macroRW
}

object InitializeRequest {
  implicit val rw: ReadWriter[InitializeRequest] = macroRW
}

object InitializeResponse {
  implicit val rw: ReadWriter[InitializeResponse] = macroRW
}

object MCPTool {
  implicit val rw: ReadWriter[MCPTool] = macroRW
}

object ToolsListResponse {
  implicit val rw: ReadWriter[ToolsListResponse] = macroRW
}

object ToolsCallRequest {
  implicit val rw: ReadWriter[ToolsCallRequest] = macroRW
}

object ToolsCallResponse {
  implicit val rw: ReadWriter[ToolsCallResponse] = macroRW
}

object MCPContent {
  implicit val rw: ReadWriter[MCPContent] = macroRW
}
