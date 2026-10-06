package org.llm4s.mcp

import scala.concurrent.duration._

/**
 * Configuration for MCP (Model Context Protocol) servers.
 * Defines how to connect to and communicate with an MCP server.
 *
 * @param name Unique identifier for this server configuration
 * @param transport Transport mechanism (stdio, SSE, or Streamable HTTP) for communication
 * @param timeout Maximum time to wait for server responses
 * @param trustAnnotations Whether the tool annotations this server sends (`readOnlyHint`, `destructiveHint`,
 *                         `idempotentHint`, `openWorldHint`) may be used to relax how its tools are treated.
 *                         `false` by default: the MCP specification says a client must consider annotations
 *                         untrusted unless they come from a trusted server, and a server that marks
 *                         `delete_everything` as read-only must not be able to run it without approval
 *                         (`ApprovalMiddleware.unlessReadOnly` skips approval for read-only tools). While it is
 *                         `false`, [[MCPClient.getToolHints]] and [[MCPToolRegistry.toolHints]] report no hints
 *                         for this server's tools, so the specification's conservative defaults
 *                         (`ToolHints.default`: not read-only, destructive, not idempotent, open-world) apply.
 *                         Set it to `true` only for a server you operate or have reviewed.
 */
case class MCPServerConfig(
  name: String,
  transport: MCPTransport,
  timeout: FiniteDuration = 30.seconds,
  trustAnnotations: Boolean = false
)

object MCPServerConfig {

  /**
   * Creates configuration for stdio-based MCP server.
   * Launches server as subprocess and communicates via stdin/stdout.
   *
   * @param name Unique identifier for this server
   *             This name will be used in transport logging for easy identification
   * @param command Command line to launch the server process
   * @param timeout Maximum response timeout
   * @param trustAnnotations Whether this server's tool annotations are trusted; see [[MCPServerConfig]]
   * @return MCPServerConfig configured for stdio transport
   */
  def stdio(
    name: String,
    command: Seq[String],
    timeout: FiniteDuration = 30.seconds,
    trustAnnotations: Boolean = false
  ): MCPServerConfig =
    MCPServerConfig(name, StdioTransport(command, name), timeout, trustAnnotations)

  /**
   * Creates configuration for Streamable HTTP-based MCP server (2025-03-26 spec).
   * Connects to server via HTTP with support for SSE streaming.
   * The client will automatically try this transport first, then fallback to SSE if not supported.
   *
   * @param name Unique identifier for this server
   * @param url HTTP URL of the MCP endpoint (single endpoint for both POST and GET)
   * @param timeout Maximum response timeout
   * @param trustAnnotations Whether this server's tool annotations are trusted; see [[MCPServerConfig]]
   * @return MCPServerConfig configured for Streamable HTTP transport with automatic fallback
   */
  def streamableHTTP(
    name: String,
    url: String,
    timeout: FiniteDuration = 30.seconds,
    trustAnnotations: Boolean = false
  ): MCPServerConfig =
    MCPServerConfig(name, StreamableHTTPTransport(url, name), timeout, trustAnnotations)

  /**
   * Creates configuration for SSE-based MCP server (legacy 2024-11-05 spec).
   * Connects to server via HTTP Server-Sent Events.
   * The client will still try the latest transport first, then fallback to this if needed.
   *
   * @param name Unique identifier for this server
   * @param url HTTP URL of the SSE endpoint
   * @param timeout Maximum response timeout
   * @param trustAnnotations Whether this server's tool annotations are trusted; see [[MCPServerConfig]]
   * @return MCPServerConfig configured for SSE transport with automatic upgrade attempt
   */
  def sse(
    name: String,
    url: String,
    timeout: FiniteDuration = 30.seconds,
    trustAnnotations: Boolean = false
  ): MCPServerConfig =
    MCPServerConfig(name, SSETransport(url, name), timeout, trustAnnotations)
}
