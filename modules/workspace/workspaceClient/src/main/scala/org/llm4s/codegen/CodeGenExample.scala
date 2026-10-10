package org.llm4s.codegen

import org.llm4s.agent.AgentResult
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMConnect
import org.slf4j.LoggerFactory

import scala.util.Using

/**
 * Example demonstrating how to use the CodeWorker to perform code tasks.
 *
 * The workspace runner runs each command without a shell, and only programs on its allowlist (#1756). `sbt` is not
 * on the default list, so this example adds it with [[CodeWorker]]'s `extraAllowedCommands`, which the container
 * receives as `WORKSPACE_EXTRA_COMMANDS=sbt`. An added program has no option rules of its own: `sbt run` runs the
 * project's code, so add a build tool only for a workspace you are prepared to let the agent build and run.
 */
object CodeGenExample {
  private val logger = LoggerFactory.getLogger(getClass)

  /** The programs this example needs besides the runner's default allowlist. */
  val ExtraAllowedCommands: Set[String] = Set("sbt")

  /** The task given to the agent. Each command it names is one program and its arguments, as the runner requires. */
  val Task: String =
    """Create a simple sbt project containing a hello world example that prints the current date and time.
      |Write the files with the file tools; create directories with 'mkdir -p'.
      |Test the generated code by running 'sbt compile' and then 'sbt run', each as its own command.
      |Commands run without a shell: give one program and its arguments, with no pipes, redirection, ';' or '&&',
      |and no 'cd' (use the working directory instead).
      |You can assume you have sbt and java already installed.
      |Run the program and show the result.""".stripMargin

  def main(args: Array[String]): Unit = {
    val task = Task

    val result = for {
      // Uses the standard loader
      ws <- WorkspaceConfigSupport.load()

      _ = logger.info(s"Using workspace directory: ${ws.workspaceDir}")

      providerCfg <- Llm4sConfig.defaultProvider()

      registryService <- Llm4sConfig.modelRegistryService()

      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)

      finalState <- Using.resource(
        new CodeWorker(ws.workspaceDir, ws.imageName, ws.hostPort, client, ExtraAllowedCommands)
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
