package org.llm4s.toolapi.builtin

/**
 * Core utility tools that require no external dependencies or API keys.
 *
 * These tools provide common functionality for agent workflows:
 *
 * - [[DateTimeTool]]: Get current date/time in any timezone
 * - [[CalculatorTool]]: Perform mathematical calculations
 * - [[UUIDTool]]: Generate unique identifiers
 * - [[JSONTool]]: Parse, format, and query JSON data
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.core._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Recommended: Safe API returning Result
 * for {
 *   dateTime   <- DateTimeTool.toolSafe
 *   calculator <- CalculatorTool.toolSafe
 *   uuid       <- UUIDTool.toolSafe
 *   json       <- JSONTool.toolSafe
 * } yield new ToolRegistry(Seq(dateTime, calculator, uuid, json))
 *
 * // Or use the convenience val
 * allToolsSafe.map(tools => new ToolRegistry(tools))
 * }}}
 */
package object core {

  /**
   * All core utility tools combined into a sequence, returning a Result for safe error handling.
   */
  val allToolsSafe: org.llm4s.types.Result[Seq[org.llm4s.toolapi.ToolFunction[_, _]]] = for {
    dateTime   <- DateTimeTool.toolSafe
    calculator <- CalculatorTool.toolSafe
    uuid       <- UUIDTool.toolSafe
    json       <- JSONTool.toolSafe
  } yield Seq(dateTime, calculator, uuid, json)

  /**
   * All core utility tools combined into a sequence.
   *
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use allToolsSafe which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  lazy val allTools: Seq[org.llm4s.toolapi.ToolFunction[_, _]] =
    Seq(DateTimeTool.tool, CalculatorTool.tool, UUIDTool.tool, JSONTool.tool)
}
