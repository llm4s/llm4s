package org.llm4s.toolapi

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.{ Await, ExecutionContext, Future, Promise, blocking }
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.unused
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
      case None => Left(ToolCallError.UnknownFunction(request.functionName, tools.map(_.name)))
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
              val delay = policy.delayBeforeAttempt(attempt)
              if (delay.toMillis > 0) {
                blocking {
                  Thread.sleep(delay.toMillis)
                }
              }
            case _ => return lastResult
          }
        }
        lastResult
    }

  /**
   * Executes a single tool attempt with an optional wall-clock timeout.
   *
   * When a timeout is configured, the tool runs on a dedicated virtual thread and this
   * method's caller thread joins it with a deadline. If the tool exceeds the deadline,
   * this method returns [[ToolCallError.Timeout]] and performs a best-effort interrupt of
   * the worker thread. The interrupt signal will unblock standard Java/Scala interruptible
   * operations such as `Thread.sleep`, socket I/O, and `Await.result`. CPU-bound
   * operations that never call a blocking primitive cannot be interrupted this way;
   * that is a JVM-level limitation. Because the worker thread is never shared with any
   * other call, a late-delivered interrupt can never leak onto unrelated work.
   *
   * @param request    The tool call request to execute.
   * @param timeoutOpt Optional maximum duration; `None` means no timeout.
   * @param ec         ExecutionContext retained for signature compatibility with the
   *                   other retry/timeout entry points; not used on the timeout path.
   */
  private def runOneAttemptWithTimeout(
    request: ToolCallRequest,
    timeoutOpt: Option[FiniteDuration]
  )(implicit @unused ec: ExecutionContext): Either[ToolCallError, ujson.Value] =
    timeoutOpt match {
      case None =>
        runOneAttempt(request)
      case Some(duration) =>
        val promise = Promise[Either[ToolCallError, ujson.Value]]()

        // The worker runs on a dedicated virtual thread instead of the shared
        // ExecutionContext's pool. That is required, not just tidier: interrupting a
        // *pooled* thread is only safe if the interrupt is delivered before the pool can
        // reuse that thread for unrelated work, and nothing guarantees that ordering under
        // load. A thread owned solely by this call cannot be reused by anything else, so an
        // interrupt delivered after it has already finished is at worst a harmless no-op
        // against a dead thread - never a leak onto a later, unrelated call.
        //
        // The timeout itself is enforced by this method's own caller thread joining the
        // worker with a deadline, rather than by a shared background scheduler. A scheduler
        // shared across every concurrent tool call is a coordination bottleneck in exactly
        // the scenario this races against - many calls timing out concurrently under CI's
        // parallel test load - since a delayed dispatch on that single shared thread looks
        // identical to a delayed interrupt. `Thread.join` has no such shared resource: each
        // call's wait is local to its own caller thread.
        val workerThread = Thread
          .ofVirtual()
          .name(s"tool-exec-${request.functionName}")
          .start { () =>
            // InterruptedException is deliberately excluded from NonFatal (it signals
            // cooperative cancellation, not a defect), but here it's the routine outcome
            // whenever the caller thread's join times out and interrupts this thread
            // mid-call, so it must be caught alongside NonFatal - otherwise it escapes
            // uncaught and `promise` is never resolved from this side. That's harmless
            // (the caller's own timeoutError below still resolves the promise), but the
            // uncaught exception would otherwise propagate to this virtual thread's
            // default uncaught-exception handler for no useful purpose.
            // scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
            val resultOrEx: Either[Throwable, Either[ToolCallError, ujson.Value]] =
              try Right(runOneAttempt(request))
              catch {
                case NonFatal(t)             => Left(t)
                case t: InterruptedException => Left(t)
              }
            // scalafix:on
            promise.trySuccess(
              resultOrEx.fold(t => Left(ToolCallError.ExecutionError(request.functionName, t)), identity)
            )
          }

        workerThread.join(duration.toMillis)
        if (workerThread.isAlive) {
          // Claim the promise for Timeout *before* interrupting the worker. If the interrupt
          // were sent first, the worker's own catch block could race ahead and call
          // promise.trySuccess(ExecutionError(InterruptedException)) before this thread's
          // trySuccess(Timeout) runs - whichever call reaches the promise first wins, and
          // interrupt delivery has no ordering guarantee relative to this thread's next line.
          // Claiming first makes the outcome deterministic: the worker's later trySuccess is
          // then always the no-op, regardless of how quickly it reacts to the interrupt.
          promise.trySuccess(Left(ToolCallError.Timeout(request.functionName, duration))): Unit
          workerThread.interrupt()
        }

        // The promise is already completed by this point - either the worker resolved it
        // before the join deadline, or the timeout branch above just did. This Await is a
        // formality that returns immediately; its duration is a defensive upper bound only.
        Await.result(promise.future, 1.second)
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

  /**
   * Creates an empty tool registry with no tools
   */
  def empty: ToolRegistry = new ToolRegistry(Seq.empty)
}
