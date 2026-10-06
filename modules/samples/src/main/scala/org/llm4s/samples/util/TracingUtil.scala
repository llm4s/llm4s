package org.llm4s.samples.util

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.trace.{ Tracing, TraceEvent }

import scala.concurrent.duration.*
import scala.util.Try

/**
 * Utility class for tracing operations
 *
 * Separates tracing boilerplate from business logic for better code readability
 * and maintainability. Provides high-level tracing methods for common scenarios.
 */
object TracingUtil {

  /** Trace the start of a demo or example */
  def traceDemoStart(tracing: Tracing, demoName: String): Unit =
    tracing.traceEvent(
      TraceEvent.CustomEvent(
        "demo_start",
        ujson.Obj(
          "demo"      -> demoName,
          "timestamp" -> BenchmarkUtil.currentTimestamp
        )
      )
    )

  /** Trace agent initialization */
  def traceAgentInitialization(tracing: Tracing, query: String, tools: Seq[ToolFunction[_, _]]): Unit =
    tracing.traceEvent(
      TraceEvent.AgentInitialized(
        query = query,
        tools = tools.map(_.name).toVector
      )
    )

  /** Trace the state an agent run ended in */
  def traceAgentStateUpdate(tracing: Tracing, result: AgentResult): Unit =
    tracing.traceEvent(
      TraceEvent.AgentStateUpdated(
        status = result.status match {
          case AgentStatus.Completed(_)     => "Complete"
          case AgentStatus.Blocked(_, _)    => "Blocked"
          case AgentStatus.StepLimitReached => "StepLimitReached"
          case AgentStatus.Suspended(_, _)  => "Suspended"
        },
        messageCount = result.messages.size,
        logCount = 0,
        messages = result.messages
      )
    )

  /** Trace successful tool execution with detailed results */
  def traceToolExecution(
    tracing: Tracing,
    toolName: String,
    operation: String,
    parameters: Map[String, String],
    result: String,
    expression: String,
    duration: FiniteDuration = 10.millis
  ): Unit = {
    val paramString = parameters.map { case (k, v) => s"$k=$v" }.mkString(", ")
    val input       = if (paramString.nonEmpty) s"$operation: $paramString" else operation

    tracing.traceEvent(
      TraceEvent.ToolExecuted(
        name = toolName,
        input = input,
        output = s"$expression = $result",
        duration = duration,
        success = true
      )
    )
  }

  /** Trace agent completion with performance metrics */
  def traceAgentCompletion(
    tracing: Tracing,
    durationMs: Long,
    steps: Vector[String],
    toolsUsed: Vector[String],
    finalResponseLength: Int
  ): Unit =
    tracing.traceEvent(
      TraceEvent.CustomEvent(
        "agent_complete",
        ujson.Obj(
          "duration_ms"           -> durationMs,
          "steps"                 -> steps.length,
          "tools_used"            -> toolsUsed.length,
          "final_response_length" -> finalResponseLength,
          "timestamp"             -> BenchmarkUtil.currentTimestamp
        )
      )
    )

  /** Trace agent step errors */
  def traceAgentStepError(
    tracing: Tracing,
    stepCount: Int,
    errorMessage: String,
    errorType: String = "LegacyLLMError"
  ): Unit =
    tracing.traceEvent(
      TraceEvent.CustomEvent(
        s"agent_step_${stepCount}_error",
        ujson.Obj(
          "error_type"    -> errorType,
          "error_message" -> errorMessage,
          "context"       -> s"agent_step_$stepCount",
          "timestamp"     -> BenchmarkUtil.currentTimestamp
        )
      )
    )

  /** Extract tool execution parameters from tool result JSON */
  def extractToolParameters(result: ujson.Value): Map[String, String] = {
    val params = scala.collection.mutable.Map[String, String]()

    result.obj.get("a").foreach(a => params += "a" -> a.num.toString)
    result.obj.get("b").foreach(b => params += "b" -> b.num.toString)

    params.toMap
  }

  /** Parsed tool result */
  case class ToolResult(
    operation: String,
    result: String,
    expression: String,
    parameters: Map[String, String]
  )

  def parseToolResult(toolContent: String): Option[ToolResult] =
    Try {
      val result = ujson.read(toolContent)
      ToolResult(
        operation = result.obj.get("operation").map(_.str).getOrElse("unknown"),
        result = result.obj.get("result").map(_.num.toString).getOrElse("unknown"),
        expression = result.obj.get("expression").map(_.str).getOrElse("unknown"),
        parameters = extractToolParameters(result)
      )
    }.toOption
}
