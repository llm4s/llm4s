package org.llm4s.codegen

import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.llmconnect.LLM
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
 * @param sourceDirectory The directory containing the codebase to work with
 */
class CodeWorker(sourceDirectory: String) {
  private val logger    = LoggerFactory.getLogger(getClass)
  private val workspace = new ContainerisedWorkspace(sourceDirectory)
  private val client    = LLM.client()
  private val agent     = new Agent(client)

  // Custom tool definitions for working with code
  private val workspaceTools = WorkspaceTools.createDefaultWorkspaceTools(workspace)
  private val toolRegistry   = new ToolRegistry(workspaceTools)

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
   * @param maxSteps Maximum number of agent steps to run (None for unlimited)
   * @param traceLogPath Optional path to write a markdown trace file
   * @return Either an error or the agent's final state
   */
  def executeTask(
    task: String,
    maxSteps: Option[Int] = None,
    traceLogPath: Option[String] = None
  ): Result[AgentState] = {
    val infoResponse = workspace.getWorkspaceInfo()
    if (infoResponse.root.isEmpty) {
      return Left(ValidationError("workspace", "Workspace is not initialized"))
    }

    logger.info(s"Executing code task: $task")
    if (traceLogPath.isDefined) {
      logger.info(s"Trace log will be written to: ${traceLogPath.get}")
    }

    // Run the agent to completion or until step limit is reached
    val result = agent.run(task, toolRegistry, maxSteps, traceLogPath, None)

    result match {
      case Right(finalState) =>
        logger.info(s"Task completed with status: ${finalState.status}")
        if (finalState.status == AgentStatus.Complete) {
          logger.info("Task completed successfully")
        } else {
          logger.warn(s"Task did not complete successfully: ${finalState.status}")
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

}
