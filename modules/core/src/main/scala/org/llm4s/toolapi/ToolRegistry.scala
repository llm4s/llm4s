package org.llm4s.toolapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.{ ExecutionContext, Future, blocking }
import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

/**
 * Request model for tool calls
 */
case class ToolCallRequest(
  functionName: String,
  arguments: ujson.Value
)

/**
 * Registry for tool functions with execution capabilities.
 *
 * Acts as the single point of truth for tools available to an agent.
 * Supports synchronous, asynchronous, and batched execution with configurable
 * concurrency strategies (see [[ToolExecutionStrategy]]):
 * - `execute()` — synchronous, blocking execution
 * - `executeAsync()` — asynchronous, non-blocking execution
 * - `executeAll()` — batch execution with a configurable [[ToolExecutionStrategy]]
 *
 * Create a registry by passing an initial set of [[ToolFunction]] instances:
 * {{{val registry = new ToolRegistry(Seq(myTool, anotherTool))
 * // or use the convenience factories:
 * ToolRegistry.empty
 * BuiltinTools.coreSafe.map(new ToolRegistry(_))
 * }}}
 *
 * @param initialTools The tools available in this registry
 */
class ToolRegistry(initialTools: Seq[ToolFunction[_, _]]) {

  /** All tools registered in this registry. */
  def tools: Seq[ToolFunction[_, _]] = initialTools

  /**
   * Get a specific tool by name
   */
  def getTool(name: String): Option[ToolFunction[_, _]] = tools.find(_.name == name)

  /**
   * Execute a tool call synchronously
   */
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
    tools.find(_.name == request.functionName) match {
      case Some(tool) =>
        Safety
          .safely(tool.execute(request.arguments))
          .left
          .map(err => ToolCallError.ExecutionError(request.functionName, new Exception(err.message)))
          .flatten
      case None => Left(ToolCallError.UnknownFunction(request.functionName))
    }

  /**
   * Execute a tool call asynchronously.
   *
   * Wraps synchronous execution in a Future for non-blocking operation.
   * NOTE: Tool execution typically involves blocking I/O.
   * We use `blocking` to hint the ExecutionContext to expand its pool if necessary.
   *
   * @param request The tool call request
   * @param ec ExecutionContext for async execution
   * @return Future containing the result
   */
  def executeAsync(request: ToolCallRequest)(implicit
    ec: ExecutionContext
  ): Future[Either[ToolCallError, ujson.Value]] =
    Future(blocking(execute(request)))

  /**
   * Execute multiple tool calls with a configurable strategy.
   *
   * @param requests The tool call requests to execute
   * @param strategy Execution strategy (Sequential, Parallel, or ParallelWithLimit)
   * @param ec ExecutionContext for async execution
   * @return Future containing results in the same order as requests
   */
  def executeAll(
    requests: Seq[ToolCallRequest],
    strategy: ToolExecutionStrategy = ToolExecutionStrategy.default
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    strategy match {
      case ToolExecutionStrategy.Sequential =>
        executeSequential(requests)

      case ToolExecutionStrategy.Parallel =>
        executeParallel(requests)

      case ToolExecutionStrategy.ParallelWithLimit(maxConcurrency) =>
        executeWithLimit(requests, maxConcurrency)
    }

  /**
   * Execute requests sequentially (one at a time).
   */
  private def executeSequential(
    requests: Seq[ToolCallRequest]
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    requests.foldLeft(Future.successful(Seq.empty[Either[ToolCallError, ujson.Value]])) { (accFuture, request) =>
      accFuture.flatMap(acc => executeAsync(request).map(result => acc :+ result))
    }

  /**
   * Execute all requests in parallel.
   */
  private def executeParallel(
    requests: Seq[ToolCallRequest]
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    Future.traverse(requests)(executeAsync)

  /**
   * Execute requests in parallel with a concurrency limit using a sliding window.
   * This implementation avoids Head-of-Line (HoL) blocking.
   */
  private def executeWithLimit(
    requests: Seq[ToolCallRequest],
    maxConcurrency: Int
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    if (requests.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val tasks        = requests.toVector
      val totalTasks   = tasks.length
      val currentIndex = new AtomicInteger(0)
      val results      = new Array[Either[ToolCallError, ujson.Value]](totalTasks)

      def worker(): Future[Unit] = {
        val idx = currentIndex.getAndIncrement()
        if (idx >= totalTasks) {
          Future.successful(())
        } else {
          val request = tasks(idx)
          executeAsync(request)
            .recover { case NonFatal(ex) =>
              Left(ToolCallError.ExecutionError(request.functionName, new Exception(ex.getMessage)))
            }
            .flatMap { result =>
              results(idx) = result
              worker()
            }
        }
      }

      val workerCount = math.min(maxConcurrency, totalTasks)
      val workers     = (1 to workerCount).map(_ => worker())

      Future.sequence(workers).map(_ => results.toSeq)
    }

  /**
   * Generate OpenAI tool definitions for all tools.
   *
   * @param strict When `true` (default), all object properties are treated as required.
   * @return A `ujson.Arr` containing one tool-definition object per registered tool
   */
  def getOpenAITools(strict: Boolean = true): ujson.Arr =
    ujson.Arr.from(tools.map(_.toOpenAITool(strict)))

  /**
   * Generate tool definitions in the format expected by a specific LLM provider.
   *
   * Currently all supported providers (`openai`, `anthropic`, `gemini`) use the
   * same OpenAI-compatible format.
   *
   * @param provider Provider name (case-insensitive): `"openai"`, `"anthropic"`, `"gemini"`
   * @return `Right(tools)` for supported providers, `Left(ValidationError)` for unsupported ones
   */
  def getToolDefinitionsSafe(provider: String): Result[ujson.Value] = provider.toLowerCase match {
    case "openai"    => Right(getOpenAITools())
    case "anthropic" => Right(getOpenAITools())
    case "gemini"    => Right(getOpenAITools())
    case _           => Left(ValidationError("provider", s"Unsupported LLM provider: $provider"))
  }

  /**
   * Generate a specific format of tool definitions for a particular LLM provider.
   *
   * @param provider Provider name (case-insensitive): `"openai"`, `"anthropic"`, `"gemini"`
   * @throws IllegalArgumentException for unsupported provider names
   */
  @deprecated("Use getToolDefinitionsSafe() which returns Result[ujson.Value] for safe error handling", "0.2.9")
  def getToolDefinitions(provider: String): ujson.Value = getToolDefinitionsSafe(provider) match {
    case Right(tools) => tools
    case Left(e)      => throw new IllegalArgumentException(e.formatted)
  }

  /**
   * Adds the tools from this registry to an Azure OpenAI ChatCompletionsOptions
   *
   * @param chatOptions The chat options to add the tools to
   * @return The updated chat options
   */
  def addToAzureOptions(
    chatOptions: com.azure.ai.openai.models.ChatCompletionsOptions
  ): com.azure.ai.openai.models.ChatCompletionsOptions =
    AzureToolHelper.addToolsToOptions(this, chatOptions)
}

object ToolRegistry {

  /**
   * Creates an empty tool registry with no tools
   */
  def empty: ToolRegistry = new ToolRegistry(Seq.empty)
}
