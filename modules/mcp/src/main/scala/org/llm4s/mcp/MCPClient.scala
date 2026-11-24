package org.llm4s.mcp

import org.llm4s.toolapi._
import org.llm4s.types.Result

/**
 * A connection to one Model Context Protocol server, whose tools it offers as llm4s
 * [[org.llm4s.toolapi.ToolFunction]]s.
 *
 * The lifecycle is [[initialize]] (the protocol handshake, once, before anything else), then [[getTools]] as often
 * as needed, then [[close]]. Each `ToolFunction` that [[getTools]] returns calls its tool on the server through
 * this client, so it works only while the client is open. Every operation that can fail returns a `Result`: a
 * server that cannot be reached is a `Left`, never an exception, and is distinct from a server with no tools
 * (`Right(Seq.empty)`).
 *
 * Most applications do not use a client directly: [[MCPToolRegistry]] holds one per configured
 * [[MCPServerConfig]], initializes it, caches its tools, and replaces a client whose server failed; pass the
 * registry to an agent's `withTools`. [[MCPClientImpl]] is the implementation, over stdio, Streamable HTTP or the
 * older HTTP+SSE transport.
 *
 * Tool hints ([[getToolHints]]) are reported only for a server configured with `trustAnnotations = true`.
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
   * before that, and empty again as soon as a listing fails or the client is closed, so a tool the server
   * no longer advertises never keeps its old hints. A tool that carries no annotations has the
   * specification's conservative defaults.
   *
   * '''Trust.''' Hints are advisory: they say what a server claims, not what its tool does, and the MCP
   * specification requires a client to treat annotations from an untrusted server as untrusted. They are
   * therefore reported only for a server configured with `MCPServerConfig.trustAnnotations = true`; for any
   * other the map is empty, which leaves `ToolHints.default` in force (approval required). Otherwise a server
   * could mark a destructive tool `readOnlyHint = true` and have `ApprovalMiddleware.unlessReadOnly` run it
   * unapproved.
   *
   * Attach hints to the tool a middleware will see with `AgentTool.fromToolFunction(tool, hints)` in
   * `llm4s-agent`.
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
