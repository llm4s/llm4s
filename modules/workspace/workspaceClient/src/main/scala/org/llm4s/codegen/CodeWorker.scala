package org.llm4s.codegen

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi._
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory
import org.llm4s.types.Result
import org.llm4s.error.ValidationError

/**
 * A worker for code generation and manipulation tasks.
 * CodeWorker combines a workspace environment with an LLM agent to handle
 * tasks involving code base understanding, modification, and generation.
 *
 * The agent's commands run in the container without a shell, and only programs on the runner's allowlist run
 * (#1756); a build tool the task needs, such as `sbt`, goes in `extraAllowedCommands`.
 *
 * @param sourceDirectory      The directory containing the codebase to work with
 * @param imageName            The workspace-runner image
 * @param hostPort             The host port for the container
 * @param client               The LLM that drives the agent
 * @param extraAllowedCommands Programs the runner may run besides its default allowlist, for example `Set("sbt")`
 */
class CodeWorker(
  sourceDirectory: String,
  imageName: String,
  hostPort: Int,
  client: LLMClient,
  extraAllowedCommands: Set[String] = Set.empty
) extends AutoCloseable {
  private val logger    = LoggerFactory.getLogger(getClass)
  private val workspace = new ContainerisedWorkspace(sourceDirectory, imageName, hostPort, extraAllowedCommands)

  // Custom tool definitions for working with code
  private val toolRegistryResult: Result[ToolRegistry] = for {
    tools <- WorkspaceTools.createDefaultWorkspaceTools(workspace)
  } yield new ToolRegistry(tools)

  /**
   * Initialize the workspace and prepare for code tasks
   * @return true if the workspace was initialized successfully
   */
  def initialize(): Boolean = {
    logger.info(s"Initializing CodeWorker for directory: $sourceDirectory")
    workspace.startContainer()
  }

  /**
   * Execute a code task and return the result
   * @param task The description of the code task to perform
   * @param maxSteps Maximum number of model calls to run (None for the agent's default)
   * @return Either an error or the agent's result
   */
  def executeTask(
    task: String,
    maxSteps: Option[Int] = None
  ): Result[AgentResult] = {
    val infoResponse = workspace.getWorkspaceInfo()
    if (infoResponse.root.isEmpty) {
      return Left(ValidationError("workspace", "Workspace is not initialized"))
    }

    logger.info(s"Executing code task: $task")

    val result = for {
      toolRegistry <- toolRegistryResult
      builder = Agent.builder("codegen", client).withTools(toolRegistry)
      agent   <- maxSteps.fold(builder)(builder.withMaxSteps).build()
      outcome <- agent.run(task)
    } yield outcome

    result match {
      case Right(outcome) =>
        logger.info(s"Task finished with status: ${outcome.status}")
        outcome.status match {
          case AgentStatus.Completed(_) => logger.info("Task completed successfully")
          case other                    => logger.warn(s"Task did not complete successfully: $other")
        }
      case Left(error) =>
        logger.error(s"Task execution failed: ${error.message}")
    }

    result
  }

  /**
   * Clean up resources when done
   */
  def shutdown(): Boolean = {
    logger.info("Shutting down CodeWorker")
    workspace.stopContainer()
  }

  override def close(): Unit =
    shutdown()
}
