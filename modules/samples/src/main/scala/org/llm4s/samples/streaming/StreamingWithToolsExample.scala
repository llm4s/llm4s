package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.agent.streaming.AgentEvent._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.slf4j.LoggerFactory

/**
 * Streaming example that demonstrates:
 * - OpenAI-style streaming output
 * - Tool calling with a simple time tool
 * - End-to-end orchestration via the Agent API (no hanging stream)
 *
 * This is intended as a reference for issue #843:
 * "[BUG] OpenAI streaming example hangs when tool calling is enabled".
 *
 * Rather than calling streamComplete directly with tools (which would only
 * surface tool_call deltas and leave orchestration to the caller), this
 * example uses Agent.runWithEvents to:
 *   - stream text deltas,
 *   - execute tools,
 *   - and stream the final answer without hanging.
 *
 * To run:
 * {{{
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-...
 * sbt "samples/runMain org.llm4s.samples.streaming.StreamingWithToolsExample"
 * }}}
 */
object StreamingWithToolsExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 60)
  logger.info("Streaming With Tools Example")
  logger.info("=" * 60)

  // Simple time tool used by the model
  final case class TimeInput(timezone: Option[String])
  final case class TimeOutput(now: String, timezone: String)

  object TimeInput {
    import upickle.default._
    implicit val rw: ReadWriter[TimeInput] = macroRW
  }

  object TimeOutput {
    import upickle.default._
    implicit val rw: ReadWriter[TimeOutput] = macroRW
  }

  private val timeToolResult = ToolBuilder[TimeInput, TimeOutput](
    name = "get_time",
    description = "Get the current time, optionally for a given timezone",
    schema = Schema
      .`object`[TimeInput]("Time query")
      .withOptionalField("timezone", Schema.string("Timezone name, e.g. Europe/London"))
  ).withHandler { extractor =>
    val tzOpt     = extractor.getString("timezone").toOption
    val zoneId    = tzOpt.getOrElse(java.time.ZoneId.systemDefault().getId)
    val now       = java.time.ZonedDateTime.now(java.time.ZoneId.of(zoneId))
    val formatted = now.toString
    Right(TimeOutput(formatted, zoneId))
  }.buildSafe()

  // Stream all important agent events so users can see progress
  private def handleEvent(event: AgentEvent): Unit = event match {
    case AgentStarted(query, toolCount, _) =>
      logger.info("[Agent] Starting with query: '{}'", query)
      logger.info("[Agent] Tools available: {}", toolCount)

    case StepStarted(stepNumber, _) =>
      logger.info("[Step {}] Starting...", stepNumber)

    case TextDelta(delta, _) =>
      // Stream text tokens as they arrive (no newline for natural UX)
      print(delta)

    case TextComplete(_, _) =>
      println()

    case ToolCallStarted(_, toolName, arguments, _) =>
      logger.info("[Tool] Calling '{}' with args: {}", toolName, arguments)

    case ToolCallCompleted(_, toolName, result, success, durationMs, _) =>
      val status = if (success) "SUCCESS" else "FAILED"
      logger.info("[Tool] '{}' {} in {}ms", toolName, status, durationMs)
      logger.info("[Tool] Result: {}", result)

    case ToolCallFailed(_, toolName, error, _) =>
      logger.error("[Tool] '{}' FAILED: {}", toolName, error)

    case StepCompleted(stepNumber, hasToolCalls, _) =>
      if (hasToolCalls) {
        logger.info("[Step {}] Completed (tool calls processed)", stepNumber)
      }

    case AgentCompleted(state, totalSteps, durationMs, _) =>
      logger.info("=" * 60)
      logger.info("[Agent] Completed in {} steps, {}ms", totalSteps, durationMs)
      logger.info("[Agent] Final status: {}", state.status)
      logger.info("=" * 60)

    case AgentFailed(error, stepNumber, _) =>
      logger.error("[Agent] FAILED at step {}: {}", stepNumber.getOrElse("unknown"), error.message)

    case _ =>
      () // Ignore other events for brevity
  }

  val result = for {
    timeTool        <- timeToolResult
    provider        <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(provider)
    agent = new Agent(client)
    tools = new ToolRegistry(Seq(timeTool))

    finalState <- agent.runWithEvents(
      query = "What time is it right now in London and New York? Answer clearly and concisely.",
      tools = tools,
      onEvent = handleEvent,
      maxSteps = Some(5)
    )
  } yield finalState

  result match {
    case Right(state) =>
      logger.info("Final Response:")
      logger.info("-" * 40)
      state.conversation.messages.lastOption.foreach(msg => logger.info("{}", msg.content))

    case Left(error) =>
      logger.error("Error: {}", error.message)
  }
}
