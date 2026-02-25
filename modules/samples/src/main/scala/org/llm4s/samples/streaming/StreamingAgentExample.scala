package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.agent.streaming.AgentEvent._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.slf4j.LoggerFactory

/**
 * Example demonstrating streaming events during agent execution.
 *
 * This example shows how to:
 * - Use `runWithEvents` for real-time streaming
 * - Handle different event types (text, tool, lifecycle)
 * - Display progress to users during long operations
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.streaming.StreamingAgentExample"
 *
 * Note: Requires LLM_MODEL and appropriate API key environment variables.
 */
object StreamingAgentExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 60)
  logger.info("Streaming Agent Example")
  logger.info("=" * 60)

  // Create a simple weather tool for demonstration
  case class WeatherInput(city: String)
  case class WeatherOutput(city: String, temperature: Int, conditions: String)

  object WeatherInput {
    import upickle.default._
    implicit val rw: ReadWriter[WeatherInput] = macroRW
  }

  object WeatherOutput {
    import upickle.default._
    implicit val rw: ReadWriter[WeatherOutput] = macroRW
  }

  val weatherToolResult = ToolBuilder[WeatherInput, WeatherOutput](
    name = "get_weather",
    description = "Get current weather for a city",
    schema = Schema.`object`[WeatherInput]("Weather query").withRequiredField("city", Schema.string("City name"))
  ).withHandler { extractor =>
    extractor.getString("city").map { city =>
      // Simulate API call delay
      Thread.sleep(500)
      val temps = Map("London" -> 15, "Paris" -> 18, "Tokyo" -> 22, "New York" -> 12)
      val temp  = temps.getOrElse(city, 20)
      WeatherOutput(city, temp, "Partly cloudy")
    }
  }.buildSafe()

  // Event handler that provides real-time feedback
  def handleEvent(event: AgentEvent): Unit = event match {
    case AgentStarted(query, toolCount, _) =>
      logger.info("[Agent] Starting with query: '{}'", query)
      logger.info("[Agent] Tools available: {}", toolCount)

    case StepStarted(stepNumber, _) =>
      logger.info("[Step {}] Starting...", stepNumber)

    case TextDelta(delta, _) =>
      // Print text as it streams (no newline) - Keep print for UI effect
      print(delta)

    case TextComplete(_, _) =>
      // Add newline after streaming completes - Keep println for UI line termination
      println()

    case ToolCallStarted(_, toolName, arguments, _) =>
      logger.info("[Tool] Calling '{}' with: {}", toolName, arguments)

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
    // Ignore other events (guardrails, handoffs)
  }

  // Run the example
  val result = for {
    weatherTool <- weatherToolResult
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)
    tools = new ToolRegistry(Seq(weatherTool))

    // Run with streaming events
    finalState <- agent.runWithEvents(
      query = "What's the weather like in London and Paris? Compare them.",
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
      System.exit(1)
  }
}
