package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentContext }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole.Tool
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating complete agent execution with multiple steps
 */
object MultiStepAgentExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Get a client using environment variables (Result-first)
    val res = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      toolRegistry = new ToolRegistry(Seq(weatherTool))
      agent        = new Agent(client)
      query = "What's the weather like in London, and is it different from New York?"
        .tap(q => logger.info("User Query: {}", q))

      _ = ".log/agent-trace.md"
        .tap(p => logger.info("Trace log will be written to: {}", p))

      _ = logger.info("=== Running Multi-Step Agent to Completion ===")
      _ = logger.info("Example 1: Running without a step limit, with trace logging")
      _ = logger.info("=== Running Multi-Step Agent with Step Limit ===")
      _ = logger.info("Example 2: Running with a step limit of 1, with trace logging")

      limitedTraceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/agent-trace-limited.md"
      _ = agent.run(
        query = query,
        tools = toolRegistry,
        maxSteps = Some(1),
        context = AgentContext(traceLogPath = Some(limitedTraceLogPath))
      ) match {
        case Right(finalState) =>
          logger.info("Final status: {}", finalState.status)

          // Print execution info
          logger.info("Total steps executed: {}", finalState.logs.size)

          // Print logs with formatted output
          if (finalState.logs.nonEmpty) {
            logger.info("Execution logs:")
            finalState.logs.foreach { log =>
              // Color-coded logs based on type
              val colorCode = log match {
                case l if l.startsWith("[assistant]") => Console.BLUE
                case l if l.startsWith("[tool]")      => Console.GREEN
                case l if l.startsWith("[tools]")     => Console.YELLOW
                case l if l.startsWith("[system]")    => Console.RED
                case _                                => Console.WHITE
              }

              logger.info("{}{}{}", colorCode, log, Console.RESET)
            }
          }

          logger.info("Trace log has been written to: {}", limitedTraceLogPath)

        case Left(error) =>
          logger.error("Error running agent: {}", error)
      }
      // Example 3: Manual step execution to show the two-phase flow
      _                  = logger.info("=== Manual Step Execution to Demonstrate Two-Phase Flow ===")
      _                  = logger.info("Example 3: Running with manual step execution")
      manualTraceLogPath = ".log/agent-trace-manual.md"
      initialState <- agent.initializeSafe(query, toolRegistry)
      _ = logger.info("Initial state: {}", initialState.status)

      _ = agent
        .writeTraceLog(initialState, manualTraceLogPath)
        .tap(_ => logger.info("Initial state written to trace log: {}", manualTraceLogPath))

      _ = logger.info("Step 1: Running LLM completion (usually generates tool calls)")
      afterLLMStep <- agent.runStep(initialState)
      _ = agent.writeTraceLog(afterLLMStep, manualTraceLogPath)
      _ = logger.info("Conversation after LLM step:")
      _ = afterLLMStep.conversation.messages.foreach(msg => logger.info("[{}] {}", msg.role, msg.content))
      _ = logger.info("Step 2: Processing tool calls")
      afterToolStep <- agent.runStep(afterLLMStep)
      _ = agent.writeTraceLog(afterToolStep, manualTraceLogPath)
      _ = logger.info("Conversation after tool execution:")
      _ = afterToolStep.conversation.messages.takeRight(2).foreach { msg =>
        logger.info("[{}] {}", msg.role, if (msg.role == Tool) msg.content.take(50) + "..." else msg.content)
      }
      _ = logger.info("The two-phase flow allows for more control and separation of concerns in the agent execution.")
      _ = logger.info("Manual trace log has been written to: {}", manualTraceLogPath)
    } yield ()
    res.fold(
      err => logger.error("Error: {}", err.formatted),
      identity
    )
  }
}
