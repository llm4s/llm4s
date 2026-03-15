package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating step-by-step agent execution for debugging
 */
object SingleStepAgentExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      toolRegistry = new ToolRegistry(Seq(weatherTool))
      agent        = new Agent(client)

      traceLogPath = ".log/single-step-trace.md"
        .tap(p => logger.info("Trace log will be written to: {}", p))

      query = "I'm planning a trip to Paris. What's the weather like there now?"
        .tap(q => logger.info("User Query: {}", q))

      _ = logger.info("=== Running Step-by-Step ===")

      initialState <- agent.initializeSafe(query, toolRegistry)
      _ = logger.info("Initial state initialized with {} messages", initialState.conversation.messages.length)

      _ = agent.writeTraceLog(initialState, traceLogPath)

      // We use a block here to run the steps and return the FINAL state
      finalState = {
        var stepCount = 0
        var state     = initialState

        while (
          (state.status == AgentStatus.InProgress || state.status == AgentStatus.WaitingForTools) && stepCount < 5
        ) {
          logger.info("Running step {}...", stepCount + 1)

          agent.runStep(state) match {
            case Right(newState) =>
              state = newState
              logger.info("Step completed with status: {}", state.status)

              state.conversation.messages.lastOption.foreach { msg =>
                val preview = msg.content.take(100) + (if (msg.content.length > 100) "..." else "")
                logger.info("Last message ({}): {}", msg.role, preview)
              }

              agent.writeTraceLog(state, traceLogPath)

            case Left(error) =>
              logger.error("Error running step: {}", error.formatted)
              state = state.withStatus(AgentStatus.Failed(error.toString))
              agent.writeTraceLog(state, traceLogPath)
          }

          stepCount += 1
        }

        logger.info("=== Step-by-Step Run Complete ===")
        logger.info("Final status: {}", state.status)
        logger.info("Total messages: {}", state.conversation.messages.length)
        logger.info("Trace log has been written to: {}", traceLogPath)

        state // Return the updated state
      }

      _ = logger.info("=== Complete Agent State Dump ===")
      _ = finalState.dump()

    } yield ()

    result.fold(
      err => logger.error("Error: {}", err.formatted),
      identity
    )
  }
}
