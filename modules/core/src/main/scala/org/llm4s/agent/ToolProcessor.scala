package org.llm4s.agent

import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.toolapi.{ ToolCallErrorJson, ToolCallRequest }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

/**
 * Tool execution variants for the agent state machine.
 *
 * == Why three execution variants coexist ==
 *
 *  - '''Synchronous''' (`processToolCalls`) is the default path used in the
 *    core `runStep` / `run` loop.  It is simple, deterministic, and safe.
 *  - '''Async/strategy-based''' (`processToolCallsAsync`) is a performance knob
 *    for agentic workloads where the LLM requests many independent tools in a
 *    single step (e.g. weather in 10 cities).  Sequential strategy degrades
 *    gracefully to the sync variant's behaviour.
 *  - '''Event-emitting''' (`processToolCallsWithEvents`) is the streaming path.
 *    It emits [[AgentEvent.ToolCallStarted]] / [[AgentEvent.ToolCallCompleted]]
 *    callbacks synchronously so the caller sees each result as it arrives,
 *    rather than batched at the end of all tool calls.  This is used by
 *    [[AgentStreamingExecutor]] for the `runWithEvents` family.
 *
 * == Why tool failures return structured JSON rather than propagating `Left` ==
 *
 * When a tool throws or returns `Left`, the error is serialised to a JSON
 * error object and stored as the tool result in the conversation.  The LLM
 * then sees the structured error message and can decide whether to retry with
 * different arguments, fall back to another tool, or inform the user.
 * Propagating `Left` immediately would terminate the entire agent run on any
 * tool exception — including transient errors and user-correctable mistakes —
 * which is almost never the right behaviour.
 */
private[agent] object ToolProcessor {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Best-effort tracing helper — failures must never affect agent control flow.
   */
  private def safeTrace(tracing: Option[Tracing])(f: Tracing => Result[Unit]): Unit =
    tracing.foreach { tracer =>
      f(tracer) match {
        case Left(error) =>
          error match {
            case UnknownError(msg, cause) =>
              logger.debug("Tracing failed: " + msg, cause)
            case _ =>
              logger.debug("Tracing failed: {}", error)
          }
        case Right(_) =>
          ()
      }
    }

  /**
   * Processes tool calls synchronously and threads the results back into `state`.
   *
   * Each tool call is executed in order; tool failures are captured as
   * structured JSON error messages rather than terminating the run.  The
   * returned state contains all tool result messages appended to the
   * conversation.
   *
   * @param state     Current agent state; its [[AgentState.tools]] registry is used.
   * @param toolCalls Tool invocations requested by the LLM.
   * @param context   Cross-cutting concerns (tracing, debug logging).
   * @return Updated state with tool results and log entries appended.
   */
  def processToolCalls(
    state: AgentState,
    toolCalls: Seq[org.llm4s.llmconnect.model.ToolCall],
    context: AgentContext
  ): AgentState = {
    val toolRegistry = state.tools

    if (context.debug) {
      logger.info("[DEBUG] processToolCalls: Processing {} tool calls", toolCalls.size)
    }

    val (finalState, toolMessages) = toolCalls.zipWithIndex.foldLeft((state, Seq.empty[ToolMessage])) {
      case ((currentState, messages), (toolCall, index)) =>
        val startTime = System.currentTimeMillis()

        if (context.debug) {
          logger.info("[DEBUG] Tool call {}/{}: {}", index + 1, toolCalls.size, toolCall.name)
          logger.info("[DEBUG]   Tool call ID: {}", toolCall.id)
          logger.info("[DEBUG]   Arguments (raw JSON): {}", toolCall.arguments)
          logger.info("[DEBUG]   Arguments type: {}", toolCall.arguments.getClass.getSimpleName)
        } else {
          logger.info("Executing tool: {} with arguments: {}", toolCall.name, toolCall.arguments)
        }

        val request = ToolCallRequest(toolCall.name, toolCall.arguments)

        if (context.debug) {
          logger.info("[DEBUG]   Created ToolCallRequest")
          logger.info("[DEBUG]   Executing via ToolRegistry...")
        }

        val result   = toolRegistry.execute(request)
        val endTime  = System.currentTimeMillis()
        val duration = endTime - startTime

        val resultContent = result match {
          case Right(json) =>
            val jsonStr = json.render()
            if (context.debug) {
              logger.info("[DEBUG]   Tool {} SUCCESS in {}ms", toolCall.name, duration)
              logger.info("[DEBUG]   Result (raw JSON): {}", jsonStr)
              logger.info("[DEBUG]   Result type: {}", json.getClass.getSimpleName)
            } else {
              logger.info("Tool {} completed successfully in {}ms. Result: {}", toolCall.name, duration, jsonStr)
            }
            safeTrace(context.tracing)(tracer =>
              tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), jsonStr)
            )
            jsonStr
          case Left(error) =>
            val errorMessage = error.getFormattedMessage
            if (context.debug) {
              logger.error("[DEBUG]   Tool {} FAILED in {}ms", toolCall.name, duration)
              logger.error("[DEBUG]   Error type: {}", error.getClass.getSimpleName)
              logger.error("[DEBUG]   Error message: {}", errorMessage)
            }
            val errorJson = ToolCallErrorJson.toJson(error).render()
            if (!context.debug) {
              logger.warn("Tool {} failed in {}ms with error: {}", toolCall.name, duration, errorMessage)
            }
            safeTrace(context.tracing)(tracer =>
              tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), errorJson)
            )
            errorJson
        }

        if (context.debug) {
          logger.info("[DEBUG]   Creating ToolMessage with ID: {}", toolCall.id)
        }

        val stateWithLog = currentState.log(s"[tool] ${toolCall.name} (${duration}ms): $resultContent")
        val toolMessage  = ToolMessage(resultContent, toolCall.id)
        (stateWithLog, messages :+ toolMessage)
    }

    if (context.debug) {
      logger.info("[DEBUG] All {} tool calls processed successfully", toolCalls.size)
      logger.info("[DEBUG] Adding {} tool messages to conversation", toolMessages.size)
    }

    finalState.addMessages(toolMessages)
  }

  /**
   * Processes tool calls asynchronously using the supplied execution strategy.
   *
   * Results are awaited with a 5-minute timeout before being folded back into
   * `state`.  This method is used by the `runWithStrategy` family where parallel
   * or rate-limited execution can reduce wall-clock time for independent tools.
   *
   * @param state     Current agent state.
   * @param toolCalls Tool invocations requested by the LLM.
   * @param strategy  Execution strategy (Sequential, Parallel, ParallelWithLimit).
   * @param context   Cross-cutting concerns.
   * @param ec        ExecutionContext for async dispatch.
   * @return Updated state with tool results appended.
   */
  def processToolCallsAsync(
    state: AgentState,
    toolCalls: Seq[org.llm4s.llmconnect.model.ToolCall],
    strategy: org.llm4s.toolapi.ToolExecutionStrategy,
    context: AgentContext
  )(implicit ec: ExecutionContext): AgentState = {
    val toolRegistry = state.tools

    if (context.debug) {
      logger.info(
        "[DEBUG] processToolCallsAsync: Processing {} tool calls with strategy {}",
        toolCalls.size,
        strategy
      )
    }

    val requests   = toolCalls.map(tc => ToolCallRequest(tc.name, tc.arguments))
    val startTimes = toolCalls.map(_ => System.currentTimeMillis())

    val resultsFuture = toolRegistry.executeAll(requests, strategy)
    val timeout       = 5.minutes
    val results       = Await.result(resultsFuture, timeout)

    val toolMessages = toolCalls.zip(results).zipWithIndex.map { case ((toolCall, result), index) =>
      val duration = System.currentTimeMillis() - startTimes(index)

      val resultContent = result match {
        case Right(json) =>
          val jsonStr = json.render()
          if (context.debug) {
            logger.info("[DEBUG] Tool {} SUCCESS in {}ms", toolCall.name, duration)
          } else {
            logger.info("Tool {} completed successfully in {}ms", toolCall.name, duration)
          }
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), jsonStr)
          )
          jsonStr

        case Left(error) =>
          val errorMessage = error.getFormattedMessage
          if (context.debug) {
            logger.error("[DEBUG] Tool {} FAILED in {}ms: {}", toolCall.name, duration, errorMessage)
          }
          val errorJson = ToolCallErrorJson.toJson(error).render()
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), errorJson)
          )
          errorJson
      }

      ToolMessage(resultContent, toolCall.id)
    }

    if (context.debug) {
      logger.info("[DEBUG] All {} tool calls processed with strategy {}", toolCalls.size, strategy)
    }

    state.addMessages(toolMessages)
  }

  /**
   * Processes tool calls synchronously, emitting [[AgentEvent]] callbacks for
   * each tool start and completion.
   *
   * Used by [[AgentStreamingExecutor]] so that the caller's `onEvent` handler
   * receives real-time tool progress notifications.  Tool failures are captured
   * as structured JSON (same as `processToolCalls`) and emit a
   * [[AgentEvent.ToolCallFailed]] event.
   *
   * @param state     Current agent state.
   * @param toolCalls Tool invocations requested by the LLM.
   * @param onEvent   Callback invoked for each [[AgentEvent]].
   * @param context   Cross-cutting concerns.
   * @return Updated state with tool results appended.
   */
  def processToolCallsWithEvents(
    state: AgentState,
    toolCalls: Seq[org.llm4s.llmconnect.model.ToolCall],
    onEvent: AgentEvent => Unit,
    context: AgentContext
  ): AgentState = {
    val toolRegistry = state.tools

    val toolMessages = toolCalls.map { toolCall =>
      val toolStartTime = System.currentTimeMillis()

      onEvent(AgentEvent.toolStarted(toolCall.id, toolCall.name, toolCall.arguments.render()))

      val request = ToolCallRequest(toolCall.name, toolCall.arguments)
      val result  = toolRegistry.execute(request)

      val toolEndTime = System.currentTimeMillis()
      val duration    = toolEndTime - toolStartTime

      val (resultContent, success) = result match {
        case Right(json) =>
          val jsonStr = json.render()
          if (context.debug) {
            logger.info("[DEBUG] Tool {} SUCCESS in {}ms", toolCall.name, duration)
          }
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), jsonStr)
          )
          (jsonStr, true)

        case Left(error) =>
          val errorMessage = error.getFormattedMessage
          val errorJson    = ToolCallErrorJson.toJson(error).render()
          if (context.debug) {
            logger.error("[DEBUG] Tool {} FAILED in {}ms: {}", toolCall.name, duration, errorMessage)
          }
          safeTrace(context.tracing)(tracer =>
            tracer.traceToolCall(toolCall.name, toolCall.arguments.render(), errorJson)
          )
          (errorJson, false)
      }

      if (success) {
        onEvent(AgentEvent.toolCompleted(toolCall.id, toolCall.name, resultContent, success = true, duration))
      } else {
        import java.time.Instant
        onEvent(AgentEvent.ToolCallFailed(toolCall.id, toolCall.name, resultContent, Instant.now()))
      }

      ToolMessage(resultContent, toolCall.id)
    }

    state.addMessages(toolMessages)
  }
}
