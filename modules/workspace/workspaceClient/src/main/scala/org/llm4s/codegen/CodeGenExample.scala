package org.llm4s.codegen

import org.llm4s.agent.AgentResult
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMConnect
import org.slf4j.LoggerFactory

import scala.util.Using

/**
 * Example demonstrating how to use the CodeWorker to perform code tasks.
 */
object CodeGenExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val task =
      """Create a simple sbt project containing a hello world example that prints the current date and time.
        |Use 'sbt compile' and 'sbt run' to test the generated code.
        |You can assume you have sbt and java already installed.
        |Run the program and show the result.""".stripMargin

    val result = for {
      // Uses the standard loader
      ws <- WorkspaceConfigSupport.load()

      _ = logger.info(s"Using workspace directory: ${ws.workspaceDir}")

      providerCfg <- Llm4sConfig.defaultProvider()

      registryService <- Llm4sConfig.modelRegistryService()

      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)

      finalState <- Using.resource(
        new CodeWorker(ws.workspaceDir, ws.imageName, ws.hostPort, client)
      ) { codeWorker =>
        for {
          _          <- Either.cond(codeWorker.initialize(), (), SimpleError("Failed to initialize CodeWorker"))
          finalState <- codeWorker.executeTask(task, Some(20))
          _ = logFinalResponse(finalState)
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

  private def logFinalResponse(result: AgentResult): Unit =
    result.answer match {
      case Some(answer) => logger.info(s"Final agent response: $answer")
      case None         => logger.warn(s"No final answer; status: ${result.status}")
    }
}
