package org.llm4s.mcp

import org.llm4s.toolapi._

/**
 * MCP clients handle the communication with MCP servers to:
 * - Establish protocol handshake and negotiate capabilities
 * - Retrieve available tools from remote servers
 * - Execute tools remotely and return results
 * - Manage connection lifecycle
 */
trait MCPClient {

  /**
   * Retrieves all available tools from the MCP server.
   * Returns tools converted to the llm4s ToolFunction format.
   *
   * @return Sequence of tool functions available from this server
   */
  def getTools(): Seq[ToolFunction[_, _]]

  /**
   * Initializes the MCP connection with handshake protocol.
   * Must be called before other operations.
   *
   * @return Either error message or successful initialization
   */
  def initialize(): Either[String, Unit]

  /**
   * Closes the MCP client connection and releases resources.
   * Should be called when done with the client.
   */
  def close(): Unit
}
