package org.llm4s.mcp

import scala.concurrent.duration._

/**
 * Configuration for MCP (Model Context Protocol) servers.
 * Defines how to connect to and communicate with an MCP server.
 *
 * @param name Unique identifier for this server configuration
 * @param transport Transport mechanism (stdio or SSE) for communication
 * @param timeout Maximum time to wait for server responses
 */
case class MCPServerConfig(
  name: String,
  transport: MCPTransport,
  timeout: Duration = 30.seconds
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
   * @return MCPServerConfig configured for stdio transport
   */
  def stdio(name: String, command: Seq[String], timeout: Duration = 30.seconds): MCPServerConfig =
    MCPServerConfig(name, StdioTransport(command, name), timeout)

  /**
   * Creates configuration for SSE-based MCP server.
   * Connects to server via HTTP Server-Sent Events.
   *
   * @param name Unique identifier for this server
   * @param url HTTP URL of the SSE endpoint
   * @param timeout Maximum response timeout
   * @return MCPServerConfig configured for SSE transport
   */
  def sse(name: String, url: String, timeout: Duration = 30.seconds): MCPServerConfig =
    MCPServerConfig(name, SSETransport(url, name), timeout)
}
