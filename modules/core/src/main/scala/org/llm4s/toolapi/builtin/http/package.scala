package org.llm4s.toolapi.builtin

/**
 * HTTP tools for making web requests.
 *
 * These tools provide safe HTTP access with configurable
 * domain restrictions and method limitations.
 *
 * == Configuration ==
 *
 * All HTTP tools accept [[HttpConfig]] for request configuration:
 *
 * - Domain allowlists and blocklists for security
 * - Allowed HTTP methods
 * - Response size limits
 * - Timeout configuration
 *
 * == Available Tools ==
 *
 * - [[HTTPTool]]: Make HTTP requests (GET, POST, PUT, DELETE, etc.)
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.http._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Read-only HTTP tool (GET/HEAD only)
 * for {
 *   readOnlyTool <- HTTPTool.createSafe(HttpConfig.readOnly())
 * } yield new ToolRegistry(Seq(readOnlyTool))
 *
 * // Restricted to specific domains
 * for {
 *   restrictedTool <- HTTPTool.createSafe(HttpConfig.restricted(
 *     Seq("api.example.com", "data.example.org")
 *   ))
 * } yield new ToolRegistry(Seq(restrictedTool))
 *
 * // Full access with custom timeout
 * for {
 *   fullTool <- HTTPTool.createSafe(HttpConfig(
 *     timeoutMs = 60000,
 *     maxResponseSize = 50 * 1024 * 1024
 *   ))
 * } yield new ToolRegistry(Seq(fullTool))
 * }}}
 */
package object http {

  /**
   * All HTTP tools with default configuration, returning a Result for safe error handling.
   */
  val allToolsSafe: org.llm4s.types.Result[Seq[org.llm4s.toolapi.ToolFunction[_, _]]] =
    HTTPTool.toolSafe.map(Seq(_))

  /**
   * All HTTP tools with default configuration.
   *
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use allToolsSafe which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  lazy val allTools: Seq[org.llm4s.toolapi.ToolFunction[_, _]] =
    Seq(HTTPTool.tool)
}
