package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentContext, AgentThread, ThreadStatus }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.chaining._

/**
 * Example demonstrating step-by-step agent execution for debugging: one model call at a time.
 *
 * `maxSteps = Some(1)` lets a turn make a single model call; the turn then ends as `Failed("Maximum step limit
 * reached")` with its checkpoint intact, and `Agent.recover` takes the next step from it - completed work is not run
 * again. The trace log is rewritten after every step.
 */
object SingleStepAgentExample {

  private val logger = LoggerFactory.getLogger(getClass)

  /** How many more steps the example takes before it gives up. */
  private val MaxSteps = 5

  def main(args: Array[String]): Unit = {
    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      toolRegistry = new ToolRegistry(Seq(weatherTool))
      agent        = new Agent(client)

      traceLogPath = ".log/single-step-trace.md"
        .tap(p => logger.info("Trace log will be written to: {}", p))
      context = AgentContext(traceLogPath = Some(traceLogPath))

      query = "I'm planning a trip to Paris. What's the weather like there now?"
        .tap(q => logger.info("User Query: {}", q))

      _ = logger.info("=== Running Step-by-Step ===")
      _ = logger.info("Running step 1...")

      first <- agent.run(query, toolRegistry, maxSteps = Some(1), context = context)
      _ = report(first)
      finalThread <- stepUntilFinished(agent, toolRegistry, first, context, MaxSteps - 1)

      _ = logger.info("=== Step-by-Step Run Complete ===")
      _ = logger.info("Final status: {}", finalThread.status)
      _ = logger.info("Total messages: {}", finalThread.messages.length)
      _ = logger.info("Trace log has been written to: {}", traceLogPath)
      _ = logger.info("=== Complete Agent Thread Dump ===")
      _ = finalThread.dump()
    } yield ()

    result.fold(
      err => logger.error("Error: {}", err.formatted),
      identity
    )
  }

  /** Takes one more step with `recover` until the turn completes, or `remaining` steps are used. */
  @tailrec
  private def stepUntilFinished(
    agent: Agent,
    tools: ToolRegistry,
    thread: AgentThread,
    context: AgentContext,
    remaining: Int
  ): Result[AgentThread] =
    if (thread.status == ThreadStatus.Completed || remaining <= 0) Right(thread)
    else {
      logger.info("Running step {}...", MaxSteps - remaining + 1)
      agent.recover(thread, tools, maxSteps = Some(1), context = context) match {
        case Right(next) =>
          report(next)
          stepUntilFinished(agent, tools, next, context, remaining - 1)
        case Left(error) =>
          Left(error)
      }
    }

  private def report(thread: AgentThread): Unit = {
    logger.info("Step completed with status: {}", thread.status)
    thread.messages.lastOption.foreach { msg =>
      val preview = msg.content.take(100) + (if (msg.content.length > 100) "..." else "")
      logger.info("Last message ({}): {}", msg.role, preview)
    }
  }
}
