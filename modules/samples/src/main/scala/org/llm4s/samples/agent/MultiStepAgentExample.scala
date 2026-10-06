package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.types.Result
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory
import scala.annotation.tailrec
import scala.util.chaining._

/**
 * Example demonstrating complete agent execution with multiple steps
 */
object MultiStepAgentExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val res = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe

      // The weather tool needs a person's approval before each call
      agent <- Agent
        .builder("multi-step-agent", client)
        .withTools(new ToolRegistry(Seq(weatherTool)))
        .withMiddleware(
          new ApprovalMiddleware(request => Some(s"'${request.spec.name}' needs approval"))
        )
        .build()

      query = "What's the weather like in London, and is it different from New York?"
        .tap(q => logger.info("User Query: {}", q))

      _ = logger.info("=== Running Multi-Step Agent with an Approval Gate ===")
      first <- agent.run(query)

      // The run suspends at the first tool call; approve what is pending until it finishes
      finished <- approveAll(agent, first)

      _ = logger.info("Final status: {}", finished.status)
      _ = logger.info("Conversation:")
      _ = finished.messages.foreach(msg => logger.info("[{}] {}", msg.role, msg.content.take(200)))
    } yield ()
    res.fold(
      err => logger.error("Error: {}", err.formatted),
      identity
    )
  }

  /** Approve every pending tool call and resume, until the run is no longer suspended. */
  @tailrec
  private def approveAll(agent: Agent, result: AgentResult): Result[AgentResult] =
    result.status match {
      case AgentStatus.Suspended(approvals, _) =>
        approvals.foreach { case (_, request) =>
          logger.info("Pending approval: {} ({})", request.call.name, request.reason)
        }
        agent.resume(result.threadId, approvals.map { case (id, _) => result.approve(id) }.toMap) match {
          case Right(next) => approveAll(agent, next)
          case left        => left
        }
      case _ => Right(result)
    }
}
