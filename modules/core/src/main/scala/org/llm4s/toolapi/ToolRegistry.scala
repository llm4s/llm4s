package org.llm4s.toolapi

import org.llm4s.core.safety.Safety

import scala.concurrent.{ExecutionContext, Future, blocking}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal // Added for safer exception handling

/**
 * Request model for tool calls
 */
case class ToolCallRequest(
  functionName: String,
  arguments: ujson.Value
)

/**
 * Registry for tool functions with execution capabilities.
 */
class ToolRegistry(initialTools: Seq[ToolFunction[_, _]]) {

  def tools: Seq[ToolFunction[_, _]] = initialTools

  def getTool(name: String): Option[ToolFunction[_, _]] =
    tools.find(_.name == name)

  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
    tools.find(_.name == request.functionName) match {
      case Some(tool) =>
        Safety
          .safely(tool.execute(request.arguments))
          .left
          .map(err => ToolCallError.ExecutionError(request.functionName, new Exception(err.message)))
          .flatten
      case None =>
        Left(ToolCallError.UnknownFunction(request.functionName))
    }

  /**
   * Execute a tool call asynchronously.
   * * NOTE: Tool execution typically involves blocking I/O. 
   * We use `blocking` to hint the ExecutionContext to expand its pool if necessary.
   */
  def executeAsync(request: ToolCallRequest)(implicit
    ec: ExecutionContext
  ): Future[Either[ToolCallError, ujson.Value]] =
    Future(blocking(execute(request)))

  def executeAll(
    requests: Seq[ToolCallRequest],
    strategy: ToolExecutionStrategy = ToolExecutionStrategy.default
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    strategy match {
      case ToolExecutionStrategy.Sequential => executeSequential(requests)
      case ToolExecutionStrategy.Parallel   => executeParallel(requests)
      case ToolExecutionStrategy.ParallelWithLimit(maxConcurrency) =>
        executeWithLimit(requests, maxConcurrency)
    }

  private def executeSequential(
    requests: Seq[ToolCallRequest]
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    requests.foldLeft(Future.successful(Seq.empty[Either[ToolCallError, ujson.Value]])) {
      (accFuture, request) =>
        accFuture.flatMap(acc => executeAsync(request).map(result => acc :+ result))
    }

  private def executeParallel(
    requests: Seq[ToolCallRequest]
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    Future.traverse(requests)(executeAsync)

  /**
   * Optimized executeWithLimit:
   * 1. Eliminates Head-of-Line (HoL) blocking using a sliding window.
   * 2. Uses `NonFatal` to prevent swallowing critical JVM errors.
   * 3. Uses `Vector` and `AtomicInteger` for thread-safe state management.
   */
  private def executeWithLimit(
    requests: Seq[ToolCallRequest],
    maxConcurrency: Int
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] = {

    require(maxConcurrency > 0, "maxConcurrency must be greater than 0")

    if (requests.isEmpty) return Future.successful(Seq.empty)

    val tasks = requests.toVector
    val totalTasks = tasks.length
    val currentIndex = new AtomicInteger(0)
    
    // Using an Array for O(1) random access. Memory footprint is O(N) where N is number of tool calls.
    // For LLMs, N is typically small enough that this is the most performant approach.
    val results = new Array[Either[ToolCallError, ujson.Value]](totalTasks)

    def worker(): Future[Unit] = {
      val idx = currentIndex.getAndIncrement()

      if (idx >= totalTasks) {
        Future.successful(())
      } else {
        val request = tasks(idx)
        executeAsync(request)
          .recover {
            // Using NonFatal is the Scala-pro way to catch only non-critical exceptions
            case NonFatal(ex) =>
              Left(ToolCallError.ExecutionError(request.functionName, new Exception(ex.getMessage)))
          }
          .flatMap { result =>
            results(idx) = result
            worker() // Asynchronous recursion: immediately takes next task from queue
          }
      }
    }

    // Spin up workers up to the concurrency limit or total tasks
    val workerCount = math.min(maxConcurrency, totalTasks)
    val workers = (1 to workerCount).map(_ => worker())

    // Combine all worker futures and return results in original order
    Future.sequence(workers).map(_ => results.toSeq)
  }

  def getOpenAITools(strict: Boolean = true): ujson.Arr =
    ujson.Arr.from(tools.map(_.toOpenAITool(strict)))

  def getToolDefinitions(provider: String): ujson.Value =
    provider.toLowerCase match {
      case "openai"    => getOpenAITools()
      case "anthropic" => getOpenAITools()
      case "gemini"    => getOpenAITools()
      case _           => throw new IllegalArgumentException(s"Unsupported provider: $provider")
    }

  def addToAzureOptions(
    chatOptions: com.azure.ai.openai.models.ChatCompletionsOptions
  ): com.azure.ai.openai.models.ChatCompletionsOptions =
    AzureToolHelper.addToolsToOptions(this, chatOptions)
}

object ToolRegistry {
  def empty: ToolRegistry = new ToolRegistry(Seq.empty)
}