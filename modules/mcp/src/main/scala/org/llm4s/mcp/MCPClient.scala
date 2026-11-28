package org.llm4s.mcp

import org.llm4s.toolapi._
import org.llm4s.types.Result

/**
 * A connection to one Model Context Protocol (MCP) server, which turns the tools the server advertises
 * into llm4s `ToolFunction`s that call back through it.
 *
 * Most applications never hold one directly: an [[MCPToolRegistry]] built from `MCPServerConfig`s creates
 * an `MCPClient` per server on first use, merges the servers' tools with local ones, and can be passed to
 * `Agent.builder(...).withTools(registry)` like any `ToolRegistry`. To use one on its own, construct
 * [[MCPClientImpl]] with an `MCPServerConfig` (stdio or HTTP transport), call [[initialize]] for the
 * protocol handshake, then [[getTools]]; each returned tool runs on the server when executed. Call
 * [[close]] when done, which closes the transport.
 *
 * Every operation that talks to the server returns a `Result`: an unreachable server is a `Left`, distinct
 * from a server with no tools (`Right(Seq.empty)`).
 */
trait MCPClient {

  /**
   * Retrieves all available tools from the MCP server.
   * Returns tools converted to the llm4s ToolFunction format.
   *
   * @return Sequence of tool functions available from this server;
   *         `Left(CancelledError)`, with the thread's interrupt flag still set, if the call was interrupted
   */
  def getTools(): Result[Seq[ToolFunction[_]]]

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
   * Checks the server answers (#3080): the handshake if it has not run, then a request that must reach the server.
   * `MCPToolRegistry.healthCheck` uses it, so a server that has died since the handshake is reported unhealthy. The
   * default lists the tools; [[MCPClientImpl]] sends `ping`, and lists the tools only if the server does not know it.
   *
   * @return `Right(())` when the server answered
   */
  def ping(): Result[Unit] = initialize().flatMap(_ => getTools().map(_ => ()))

  /**
   * Closes the MCP client connection and releases resources.
   * Should be called when done with the client.
   */
  def close(): Unit
}
