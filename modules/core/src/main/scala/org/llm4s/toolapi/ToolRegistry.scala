package org.llm4s.toolapi

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.{ Await, ExecutionContext, Future, Promise, blocking }
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Executors, ScheduledExecutorService, TimeUnit }
import scala.util.control.NonFatal

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
   * Executes a tool call synchronously, wrapping any thrown exception.
   *
   * We use `Try` here (rather than `org.llm4s.core.safety.Safety.safely`) so that tool
   * execution remains independent of the Safety API and returns `Either` for direct use
   * by retry/timeout logic. Safety is still used elsewhere in the codebase (e.g. tracing,
   * agent entry points).
   *
   * Exceptions thrown inside the tool implementation are caught and converted to
   * `ToolCallError.ExecutionError` with the original throwable preserved (so
   * retry logic can treat e.g. IOException as retryable). Callers always receive
   * a typed `Either` and never need to guard against unexpected exceptions from tool code.
   *
   * Tool-returned `Left` values are propagated unchanged, so callers may receive
   * any `ToolCallError` subtype that the tool itself produces (not only `ExecutionError`).
   *
   * @param request The tool name and pre-parsed JSON arguments.
   * @return `Right(result)` on success; `Left(ToolCallError.UnknownFunction)`
   *         when no tool with the given name is registered;
   *         `Left(ToolCallError.ExecutionError)` when the tool throws an
   *         exception; or `Left(error)` with the tool's own `ToolCallError` when
   *         the tool returns a `Left` directly.
   */
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
    runOneAttempt(request)

  /**
   * Executes a tool call with optional per-tool timeout and retry.
   *
   * When `config` is default (no timeout, no retry), behavior is identical to [[execute(request)]].
   *
   * @param request The tool call request
   * @param config  Optional timeout and retry policy
   * @param ec      ExecutionContext required when timeout or retry is used
   */
  def execute(
    request: ToolCallRequest,
    config: ToolExecutionConfig
  )(implicit ec: ExecutionContext): Either[ToolCallError, ujson.Value] = {
    val noTimeout = config.timeout.isEmpty
    val noRetry   = config.retryPolicy.isEmpty
    if (noTimeout && noRetry) {
      runOneAttempt(request)
    } else {
      runWithRetry(request, config)
    }
  }

  /** Single attempt: find tool and run it (no timeout, no retry). */
  private def runOneAttempt(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
    tools.find(_.name == request.functionName) match {
      case Some(tool) =>
        scala.util.Try(tool.execute(request.arguments)) match {
          case scala.util.Success(Right(v)) => Right(v)
          case scala.util.Success(Left(e))  => Left(e)
          case scala.util.Failure(t)        => Left(ToolCallError.ExecutionError(request.functionName, t))
        }
      case None => Left(ToolCallError.UnknownFunction(request.functionName))
    }

  private def runWithRetry(
    request: ToolCallRequest,
    config: ToolExecutionConfig
  )(implicit ec: ExecutionContext): Either[ToolCallError, ujson.Value] =
    config.retryPolicy match {
      case None =>
        runOneAttemptWithTimeout(request, config.timeout)
      case Some(policy) =>
        var attempt                                        = 0
        var lastResult: Either[ToolCallError, ujson.Value] = null
        while (attempt < policy.maxAttempts) {
          lastResult = runOneAttemptWithTimeout(request, config.timeout)
          lastResult match {
            case Right(_) => return lastResult
            case Left(err) if ToolCallError.isRetryable(err) && attempt + 1 < policy.maxAttempts =>
              attempt += 1
              // Exponential backoff: delay = baseDelay * backoffFactor^(attempt-1)
              // attempt 1 -> baseDelay, attempt 2 -> baseDelay * factor, attempt 3 -> baseDelay * factor^2, ...
              val delayMs =
                (policy.baseDelay.toMillis * math.pow(policy.backoffFactor, (attempt - 1).toDouble)).toLong
              if (delayMs > 0) {
                blocking {
                  Thread.sleep(delayMs)
                }
              }
            case _ => return lastResult
          }
        }
        lastResult
    }

  private def runOneAttemptWithTimeout(
    request: ToolCallRequest,
    timeoutOpt: Option[FiniteDuration]
  )(implicit ec: ExecutionContext): Either[ToolCallError, ujson.Value] =
    timeoutOpt match {
      case None =>
        runOneAttempt(request)
      case Some(duration) =>
        val promise = Promise[Either[ToolCallError, ujson.Value]]()
        val timeoutError =
          Left(ToolCallError.Timeout(request.functionName, duration)): Either[ToolCallError, ujson.Value]
        val runFuture = Future(blocking(runOneAttempt(request)))
        val scheduled = ToolRegistry.timeoutScheduler.schedule(
          new Runnable {
            override def run(): Unit = promise.trySuccess(timeoutError)
          },
          duration.length,
          duration.unit
        )
        runFuture.onComplete { result =>
          scheduled.cancel(false)
          promise.tryComplete(result)
        }
        Await.result(promise.future, duration + 1.second)
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
    executeAsync(request, ToolExecutionConfig())

  /**
   * Execute a tool call asynchronously with optional timeout and retry.
   *
   * @param request The tool call request
   * @param config  Optional timeout and retry policy
   * @param ec      ExecutionContext for async execution
   */
  def executeAsync(
    request: ToolCallRequest,
    config: ToolExecutionConfig
  )(implicit ec: ExecutionContext): Future[Either[ToolCallError, ujson.Value]] =
    Future(blocking(execute(request, config)))

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
    strategy: ToolExecutionStrategy = ToolExecutionStrategy.default,
    config: ToolExecutionConfig = ToolExecutionConfig()
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    strategy match {
      case ToolExecutionStrategy.Sequential =>
        executeSequential(requests, config)

      case ToolExecutionStrategy.Parallel =>
        executeParallel(requests, config)

      case ToolExecutionStrategy.ParallelWithLimit(maxConcurrency) =>
        executeWithLimit(requests, maxConcurrency, config)
    }

  /**
   * Execute requests sequentially (one at a time).
   */
  private def executeSequential(
    requests: Seq[ToolCallRequest],
    config: ToolExecutionConfig
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    requests.foldLeft(Future.successful(Seq.empty[Either[ToolCallError, ujson.Value]])) { (accFuture, request) =>
      accFuture.flatMap(acc => executeAsync(request, config).map(result => acc :+ result))
    }

  /**
   * Execute all requests in parallel.
   */
  private def executeParallel(
    requests: Seq[ToolCallRequest],
    config: ToolExecutionConfig
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    Future.traverse(requests)(req => executeAsync(req, config))

  /**
   * Execute requests in parallel with a concurrency limit using a sliding window.
   * This implementation avoids Head-of-Line (HoL) blocking.
   */
  private def executeWithLimit(
    requests: Seq[ToolCallRequest],
    maxConcurrency: Int,
    config: ToolExecutionConfig
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
          executeAsync(request, config)
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
   * @throws java.lang.IllegalArgumentException for unsupported provider names
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

  /** Shared scheduler for per-tool timeouts. Single thread, no thread per tool call. */
  private[toolapi] lazy val timeoutScheduler: ScheduledExecutorService = {
    val executor = Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
      val t = new Thread(r, "tool-registry-timeout")
      t.setDaemon(true)
      t
    }
    sys.addShutdownHook {
      executor.shutdown()
      try
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          executor.shutdownNow()
        }
      catch {
        case _: InterruptedException =>
          executor.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }
    executor
  }

  /**
   * Creates an empty tool registry with no tools
   */
  def empty: ToolRegistry = new ToolRegistry(Seq.empty)
}
