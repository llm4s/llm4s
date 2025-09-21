package org.llm4s.codegen

import org.llm4s.agent.AgentState
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.model.MessageRole
import org.slf4j.LoggerFactory

import scala.util.Using

/**
 * Example demonstrating how to use the CodeWorker to perform code tasks.
 */
object CodeGenExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // TODO need to get workspace and traceLogPath dir from config/env vars

    val workspaceDir = System.getProperty("user.home") + "/code-workspace"
    logger.info(s"Using workspace directory: $workspaceDir")

    val traceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/codegen-trace.md"
    logger.info(s"Trace log will be written to: $traceLogPath")

    val task =
      """Create a simple sbt project containing a hello world example that prints the current date and time.
        |Use 'sbt compile' and 'sbt run' to test the generated code.
        |You can assume you have sbt and java already installed.
        |Run the program and show the result.""".stripMargin

    val result = for {
      config <- LLMConfig()
      finalState <- Using.resource(new CodeWorker(workspaceDir)(config)) { codeWorker =>
        for {
          _          <- Either.cond(codeWorker.initialize(), (), SimpleError("Failed to initialize CodeWorker"))
          finalState <- codeWorker.executeTask(task, Some(20), Some(traceLogPath))
          _ = logFinalResponse(finalState, traceLogPath)
        } yield finalState
      }
    } yield finalState

    result match {
      case Right(finalState) =>
        logger.info(s"Workflow completed successfully. Final status: ${finalState.status}")
      case Left(err) =>
        logger.error(s"Workflow failed: ${err.message}")
    }
  }

  private def logFinalResponse(finalState: AgentState, traceLogPath: String): Unit = {
    finalState.conversation.messages.lastOption match {
      case Some(msg) if msg.role == MessageRole.Assistant =>
        logger.info(s"Final agent response: ${msg.content}")
      case _ =>
        logger.warn("No final assistant message found")
    }

    if (finalState.logs.nonEmpty) {
      logger.info(s"Execution logs (see also $traceLogPath):")
      finalState.logs.foreach(logger.info)
    }
  }
}
