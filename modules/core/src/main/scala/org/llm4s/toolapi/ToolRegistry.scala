package org.llm4s.toolapi

import org.llm4s.annotation.Stable
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result

/**
 * Carries the name and parsed JSON arguments for a single tool invocation.
 *
 * Produced by the agent framework from [[org.llm4s.llmconnect.model.ToolCall]]
 * values in the LLM response. `arguments` is a pre-parsed `ujson.Value` (not a
 * raw JSON string), so tool implementations receive structured data directly.
 *
 * @param functionName Name of the tool to invoke; matched against [[ToolFunction.name]]
 *                     in [[ToolRegistry.execute]].
 * @param arguments    Parsed JSON arguments; typically a JSON object whose fields
 *                     correspond to the tool's declared [[Schema]].
 */
@Stable
final case class ToolCallRequest private (
  functionName: String,
  arguments: ujson.Value
) {

  /** Replace the name of the tool to invoke. */
  def withFunctionName(functionName: String): ToolCallRequest = copy(functionName = functionName)

  /** Replace the parsed JSON arguments. */
  def withArguments(arguments: ujson.Value): ToolCallRequest = copy(arguments = arguments)
}

object ToolCallRequest {

  /** Creates a [[ToolCallRequest]]. */
  def apply(functionName: String, arguments: ujson.Value): ToolCallRequest =
    new ToolCallRequest(functionName, arguments)
}

/**
 * The tools a model may call, by name, and the way to run one call.
 *
 * Build one with [[ToolRegistry.of]], which rejects two tools with the same name: a model is offered every
 * tool by name, so the second tool of a name could never be called.
 * {{{
 * val registry: Result[ToolRegistry] = ToolRegistry.of(Seq(myTool, anotherTool))
 * BuiltinTools.coreSafe.flatMap(ToolRegistry.of)
 * }}}
 *
 * A registry runs one call at a time, on the caller's thread. Timeouts, retries, asynchronous and batched
 * execution are [[ToolExecutor]]'s, which works on any registry. The definitions a provider is sent are
 * rendered from [[tools]] with [[ToolFunction.toolDefinitions]].
 *
 * Another source of tools implements this trait (`MCPToolRegistry` in `llm4s-mcp` does); its `tools` must
 * not name a tool twice either.
 */
@Stable
trait ToolRegistry {

  /** All tools in this registry; no two share a name. */
  def tools: Seq[ToolFunction[_, _]]

  /** The tool called `name`, if this registry has one. */
  def tool(name: String): Option[ToolFunction[_, _]] = tools.find(_.name == name)

  /**
   * Executes a tool call synchronously, on the calling thread.
   *
   * An exception the tool throws is caught and returned as [[ToolCallError.ExecutionError]] with the throwable
   * preserved, so a caller never guards against exceptions from tool code. A call that ends while its thread
   * is interrupted is [[ToolCallError.Cancelled]], whatever it returned, and the interrupt flag is left set.
   *
   * @param request The tool name and pre-parsed JSON arguments.
   * @return `Right(result)` on success; `Left(ToolCallError.UnknownFunction)` when no tool with the given name
   *         is registered; otherwise the `ToolCallError` the call ended with.
   */
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value]
}

object ToolRegistry {

  /**
   * A registry of `tools`, in the order given.
   *
   * @return `Left(ValidationError)` naming every tool name that appears more than once
   */
  def of(tools: Seq[ToolFunction[_, _]]): Result[ToolRegistry] = {
    val names      = tools.map(_.name)
    val duplicated = names.diff(names.distinct).distinct
    if (duplicated.isEmpty) Right(new Fixed(tools.toVector))
    else
      Left(
        ValidationError(
          "tools",
          s"tool names must be unique, but these are defined more than once: ${duplicated.mkString(", ")}"
        )
      )
  }

  private val Empty: ToolRegistry = new Fixed(Vector.empty)

  /** A registry with no tools. */
  def empty: ToolRegistry = Empty

  /**
   * Runs `tool` for `request` on the calling thread, turning a thrown exception into
   * [[ToolCallError.ExecutionError]] and an interruption into [[ToolCallError.Cancelled]] with the interrupt
   * flag restored. Every [[ToolRegistry]] in llm4s runs its tools through this, so cancellation reads the same
   * from all of them.
   *
   * `Try` rather than `org.llm4s.error.Safety.safely`: the throwable is kept as it was thrown, so that
   * retry logic can treat for example an `IOException` as retryable.
   */
  private[llm4s] def run(tool: ToolFunction[_, _], request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
    CancelledError.catchInterrupt(scala.util.Try(tool.execute(request.arguments))) match {
      case Left(_) =>
        // The throw cleared the flag; restore it so the caller still sees the cancellation.
        Thread.currentThread().interrupt()
        Left(ToolCallError.Cancelled(request.functionName))
      case Right(_) if Thread.currentThread().isInterrupted =>
        Left(ToolCallError.Cancelled(request.functionName))
      case Right(scala.util.Success(result)) => result
      // an interruption the tool wrapped is still a cancellation, never retried; the tool caught it on
      // this thread and so cleared the flag, so restore it as for a direct throw (design section 4.4)
      case Right(scala.util.Failure(t)) if CancelledError.isCancellation(t) =>
        Thread.currentThread().interrupt()
        Left(ToolCallError.Cancelled(request.functionName))
      case Right(scala.util.Failure(t)) => Left(ToolCallError.ExecutionError(request.functionName, t))
    }

  final private class Fixed(val tools: Vector[ToolFunction[_, _]]) extends ToolRegistry {
    def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
      tool(request.functionName) match {
        case Some(found) => ToolRegistry.run(found, request)
        case None        => Left(ToolCallError.UnknownFunction(request.functionName, tools.map(_.name)))
      }
  }
}
