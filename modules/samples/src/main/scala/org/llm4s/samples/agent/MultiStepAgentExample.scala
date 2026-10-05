package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentContext, AgentThread }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating complete agent execution with multiple steps: a run to completion, a run cut short by a step
 * limit, and the cut-short run continued with `recover`.
 */
object MultiStepAgentExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Get a client using environment variables (Result-first)
    val res = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      toolRegistry = new ToolRegistry(Seq(weatherTool))
      agent        = new Agent(client)
      query = "What's the weather like in London, and is it different from New York?"
        .tap(q => logger.info("User Query: {}", q))

      _         = logger.info("=== Example 1: running to completion, with trace logging ===")
      tracePath = ".log/agent-trace.md".tap(p => logger.info("Trace log will be written to: {}", p))
      complete <- agent.run(query, toolRegistry, context = AgentContext(traceLogPath = Some(tracePath)))
      _ = logger.info("Final status: {}", complete.status)
      _ = describe(complete)

      _ = logger.info("=== Example 2: a step limit of 1 (one model call), then continued with recover ===")
      limitedTracePath = ".log/agent-trace-limited.md"
      limited <- agent.run(
        query = query,
        tools = toolRegistry,
        maxSteps = Some(1),
        context = AgentContext(traceLogPath = Some(limitedTracePath))
      )
      _ = logger.info("Status after one model call: {}", limited.status)
      _ = describe(limited)

      resumed <- agent.recover(
        limited,
        toolRegistry,
        context = AgentContext(traceLogPath = Some(limitedTracePath))
      )
      _ = logger.info("Status after recover: {}", resumed.status)
      _ = describe(resumed)
      _ = logger.info("Trace log has been written to: {}", limitedTracePath)
      _ = logger.info("The first model call was not made again: recover continues from the checkpoint.")
    } yield ()
    res.fold(
      err => logger.error("Error: {}", err.formatted),
      identity
    )
  }

  /** Prints the conversation, colour-coded by role. */
  private def describe(thread: AgentThread): Unit = {
    logger.info("Total messages: {}, usage: {} requests", thread.messages.length, thread.usage.requestCount)
    thread.messages.foreach { msg =>
      val colorCode = msg.role match {
        case MessageRole.Assistant => Console.BLUE
        case MessageRole.Tool      => Console.GREEN
        case MessageRole.User      => Console.YELLOW
        case MessageRole.System    => Console.RED
      }
      val text = if (msg.role == MessageRole.Tool) msg.content.take(50) + "..." else msg.content
      logger.info("{}[{}] {}{}", colorCode, msg.role, text, Console.RESET)
    }
  }
}
