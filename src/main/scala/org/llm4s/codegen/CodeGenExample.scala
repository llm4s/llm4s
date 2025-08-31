package org.llm4s.codegen

import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.config.ConfigReader.LLMConfig
import org.slf4j.LoggerFactory

/**
 * Example demonstrating how to use the CodeWorker to perform code tasks.
 */
object CodeGenExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Sample workspace directory - user's home directory or current directory
    val workspaceDir = System.getProperty("user.home") + "/code-workspace"
    logger.info(s"Using workspace directory: $workspaceDir")

    // Create a CodeWorker instance
    val codeWorker = new CodeWorker(workspaceDir)(LLMConfig())

    // Define the trace log path
    val traceLogPath = "/Users/rory.graves/workspace/home/llm4s/log/codegen-trace.md"
    logger.info(s"Trace log will be written to: $traceLogPath")

    try
      // Initialize the workspace
      if (codeWorker.initialize()) {
        logger.info("CodeWorker initialized successfully")

        // Define a code task - could be creating a new file, modifying code, etc.
        val task =
          "Create a simple sbt project containing a hello world example that prints the current date and time.  Use 'sbt compile' and 'sbt run' to test the generated code.  You can assume you have sbt and java already installed." +
            "Run the program and show the result. "

        // Execute the task with trace logging - increase step limit to 20
        codeWorker.executeTask(task, maxSteps = Some(20), traceLogPath = Some(traceLogPath)) match {
          case Right(finalState) =>
            logger.info(s"Task execution completed. Final status: ${finalState.status}")
            logger.info(s"Trace log has been written to: $traceLogPath")

            // Print the agent's final response
            finalState.conversation.messages.last match {
              case msg if msg.role == MessageRole.Assistant =>
                logger.info(s"Final agent response: ${msg.content}")
              case _ =>
                logger.warn("No final assistant message found")
            }

            // Print execution logs for debugging
            if (finalState.logs.nonEmpty) {
              logger.info("Execution logs:")
              finalState.logs.foreach(log => logger.info(log))
            }

          case Left(error) =>
            logger.error(s"Task execution failed: ${error.message}")
        }
      } else {
        logger.error("Failed to initialize CodeWorker")
      }
    catch {
      case e: Exception =>
        logger.error(s"Error during code task execution: ${e.getMessage}", e)
    } finally
      // Clean up resources
      if (codeWorker.shutdown()) {
        logger.info("CodeWorker shutdown successfully")
      } else {
        logger.error("Failed to shutdown CodeWorker properly")
      }
  }
}
