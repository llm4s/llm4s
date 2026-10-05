package org.llm4s.mcp

import org.llm4s.toolapi._
import org.llm4s.types.Result

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
   * @return Sequence of tool functions available from this server;
   *         `Left(CancelledError)`, with the thread's interrupt flag still set, if the call was interrupted
   */
  def getTools(): Result[Seq[ToolFunction[_, _]]]

  /**
   * The hints each tool declares through its MCP annotations (`readOnlyHint`, `destructiveHint`,
   * `idempotentHint`, `openWorldHint`), by tool name, as of the last successful [[getTools]]; empty
   * before that. A tool that carries no annotations has the specification's conservative defaults.
   *
   * Hints are advisory: they say what a server claims, not what its tool does. Attach them to the
   * tool a middleware will see with `AgentTool.fromToolFunction(tool, hints)` in `llm4s-agent`.
   */
  def getToolHints(): Map[String, ToolHints] = Map.empty

  /**
   * Initializes the MCP connection with handshake protocol.
   * Must be called before other operations.
   *
   * @return the error that stopped the handshake, or successful initialization;
   *         `Left(CancelledError)`, with the thread's interrupt flag still set, if the call was interrupted
   */
  def initialize(): Result[Unit]

  /**
   * Closes the MCP client connection and releases resources.
   * Should be called when done with the client.
   */
  def close(): Unit
}
