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
  jsonrpc: String, // No default value to force explicit setting
  id: String,
  method: String,
  params: Option[ujson.Value] = None
)

/**
 * JSON-RPC 2.0 notification structure for MCP protocol communication.
 * Notifications are requests without an ID - the client doesn't expect a response.
 * Used for fire-and-forget messages like the "initialized" notification.
 *
 * @param jsonrpc Protocol version, always "2.0"
 * @param method The method name to invoke on the server
 * @param params Optional parameters for the method
 */
case class JsonRpcNotification(
  jsonrpc: String, // No default value to force explicit setting
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
 * @param roots Root directory access capabilities
 * @param sampling Sampling capabilities
 */
case class MCPCapabilities(
  tools: Option[ujson.Value] = Some(ujson.Obj()),
  logging: Option[ujson.Value] = None,
  prompts: Option[ujson.Value] = None,
  resources: Option[ujson.Value] = None,
  roots: Option[ujson.Value] = Some(ujson.Obj("listChanged" -> ujson.Bool(false))),
  sampling: Option[ujson.Value] = Some(ujson.Obj())
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
 * Enhanced content item within a tool response (PR #371 - Structured Tool Output).
 * Supports text, resource links, and structured annotations.
 *
 * @param `type` Content type: "text", "resource", "image", etc.
 * @param text The actual content text (for text type)
 * @param resource Resource reference (for resource type)
 * @param annotations Optional structured metadata
 */
case class MCPContent(
  `type`: String,
  text: Option[String] = None,
  resource: Option[ResourceReference] = None,
  annotations: Option[ujson.Value] = None
)

/**
 * Reference to an MCP resource (PR #603 - Resource links in tool results).
 *
 * @param uri URI of the referenced resource
 * @param `type` Optional MIME type or resource type
 */
case class ResourceReference(
  uri: String,
  `type`: Option[String] = None
)

/**
 * Standard JSON-RPC and MCP-specific error codes.
 */
object MCPErrorCodes {
  // Standard JSON-RPC 2.0 error codes
  val PARSE_ERROR      = -32700
  val INVALID_REQUEST  = -32600
  val METHOD_NOT_FOUND = -32601
  val INVALID_PARAMS   = -32602
  val INTERNAL_ERROR   = -32603

  // MCP-specific error codes (range -32000 to -32099)
  val INVALID_PROTOCOL_VERSION = -32000
  val TOOL_NOT_FOUND           = -32001
  val TOOL_EXECUTION_ERROR     = -32002
  val SESSION_EXPIRED          = -32003
  val UNAUTHORIZED             = -32004
  val RESOURCE_NOT_FOUND       = -32005
  val TRANSPORT_ERROR          = -32006
  val TIMEOUT_ERROR            = -32007

  def getErrorMessage(code: Int): String = code match {
    case PARSE_ERROR              => "Parse error"
    case INVALID_REQUEST          => "Invalid Request"
    case METHOD_NOT_FOUND         => "Method not found"
    case INVALID_PARAMS           => "Invalid params"
    case INTERNAL_ERROR           => "Internal error"
    case INVALID_PROTOCOL_VERSION => "Invalid protocol version"
    case TOOL_NOT_FOUND           => "Tool not found"
    case TOOL_EXECUTION_ERROR     => "Tool execution error"
    case SESSION_EXPIRED          => "Session expired"
    case UNAUTHORIZED             => "Unauthorized"
    case RESOURCE_NOT_FOUND       => "Resource not found"
    case TRANSPORT_ERROR          => "Transport error"
    case TIMEOUT_ERROR            => "Timeout error"
    case _                        => s"Unknown error ($code)"
  }

  // Create a proper JSON-RPC error response
  def createError(code: Int, message: String, data: Option[ujson.Value] = None): JsonRpcError =
    JsonRpcError(code, message, data)

  // Helper methods for common errors
  def transportError(message: String): JsonRpcError =
    createError(TRANSPORT_ERROR, message)

  def timeoutError(message: String): JsonRpcError =
    createError(TIMEOUT_ERROR, message)

  def toolNotFound(toolName: String): JsonRpcError =
    createError(TOOL_NOT_FOUND, s"Tool not found: $toolName")

  def toolExecutionError(toolName: String, error: String): JsonRpcError =
    createError(TOOL_EXECUTION_ERROR, s"Tool execution failed: $toolName - $error")
}

// Serialization support for JSON marshalling/unmarshalling
object JsonRpcRequest {
  implicit val rw: ReadWriter[JsonRpcRequest] = macroRW
}

object JsonRpcNotification {
  implicit val rw: ReadWriter[JsonRpcNotification] = macroRW
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

object ResourceReference {
  implicit val rw: ReadWriter[ResourceReference] = macroRW
}
