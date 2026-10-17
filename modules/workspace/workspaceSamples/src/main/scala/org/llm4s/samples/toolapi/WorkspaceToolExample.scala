package org.llm4s.samples.toolapi

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.agent.harness.{ ExecuteTool, FileTools }
import org.llm4s.agent.harness.workspace.{ WorkspaceFileBackend, WorkspaceSandboxBackend }
import org.llm4s.codegen.WorkspaceConfigSupport
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory

import scala.util.{ Try, Using }

/**
 * An agent with the harness's file tools and `execute` over a containerised workspace (design §4.33), run with
 * different models.
 *
 * It runs the same prompt with the default named provider section (`llm4s.providers.provider`),
 * then with each named section given as an argument - by default `openai-main` and
 * `anthropic-main` - loaded with `Llm4sConfig.provider(name)`. A section that is not configured
 * is skipped with its error. Add the sections to the samples' git-ignored
 * `application.local.conf` (docs/getting-started/configuration.md#running-the-samples):
 *
 * {{{
 * llm4s.providers {
 *   openai-main {
 *     provider = "openai"
 *     model    = "gpt-4o"
 *     apiKey   = ${?OPENAI_API_KEY}
 *   }
 *   anthropic-main {
 *     provider = "anthropic"
 *     model    = "claude-sonnet-4-20250514"
 *     apiKey   = ${?ANTHROPIC_API_KEY}
 *   }
 * }
 * }}}
 *
 * Needs Docker and the workspace runner image (see `WorkspaceConfigSupport`). To run:
 * {{{
 * sbt "workspaceSamples/runMain org.llm4s.samples.toolapi.WorkspaceToolExample"
 * sbt "workspaceSamples/runMain org.llm4s.samples.toolapi.WorkspaceToolExample openai-main"
 * }}}
 */
object WorkspaceToolExample {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Named provider sections run after the default one when no section names are given as arguments. */
  val DefaultComparisonSections: Seq[String] = Seq("openai-main", "anthropic-main")

  private val Prompt =
    "Please list the files at /, read /test_file.txt, and search for the word 'test' across all files with grep. " +
      "Then run 'wc -l test_file.txt' with execute. Return a summary of what you found."

  def main(args: Array[String]): Unit = {
    val comparisonSections = if (args.nonEmpty) args.toSeq else DefaultComparisonSections

    val ws =
      WorkspaceConfigSupport
        .load()
        .fold(
          err => throw new IllegalArgumentException(s"Failed to load workspace settings: ${err.formatted}"),
          identity
        )
    logger.info(s"Using workspace directory: ${ws.workspaceDir}")

    val workspace = new ContainerisedWorkspace(ws.workspaceDir, ws.imageName, ws.hostPort)

    val result = Using.resource(workspace) { ws =>
      Try {
        if (!ws.startContainer()) logger.error("Failed to start the workspace container")
        else {
          logger.info("Container started successfully")
          ws.writeFile("test_file.txt", "This is a test file\nIt has multiple lines\nFor testing search functionality")
          ws.writeFile("another_file.txt", "This is another test file\nWith different content")

          // the file tools over the workspace's files, and execute over its sandbox
          val toolsRes = for {
            files   <- WorkspaceFileBackend("sample", ws)
            sandbox <- WorkspaceSandboxBackend("sample", ws)
            tools   <- ToolSet.of((FileTools(files) :+ ExecuteTool(sandbox))*)
          } yield tools

          toolsRes match {
            case Left(err) => logger.error("Failed to create the workspace tools: {}", err.formatted)
            case Right(tools) =>
              val registryServiceRes = Llm4sConfig.modelRegistryService()

              logger.info("Attempting the default provider from configuration (llm4s.providers.provider)...")
              registryServiceRes.flatMap { registryService =>
                given org.llm4s.model.ModelRegistryService = registryService
                Llm4sConfig.defaultProvider().flatMap(cfg => LLMConnect.getClient(cfg).map(_ -> cfg.model))
              } match {
                case Right((client, model)) =>
                  logger.info(s"Running the workspace demo with the active model: $model")
                  run(client, tools)
                case Left(err) => logger.warn(s"Active provider client setup skipped: ${err.formatted}")
              }

              comparisonSections.foreach { sectionName =>
                logger.info(s"Testing with named provider section '$sectionName'...")
                registryServiceRes.flatMap { registryService =>
                  given org.llm4s.model.ModelRegistryService = registryService
                  Llm4sConfig.provider(sectionName).flatMap(LLMConnect.getClient)
                } match {
                  case Right(client) => run(client, tools)
                  case Left(err) =>
                    logger.error(s"Section '$sectionName' skipped: ${err.formatted}")
                    logger.info(
                      s"Add a '$sectionName' section under llm4s.providers in the samples' application.local.conf - " +
                        "see docs/getting-started/configuration.md#running-the-samples"
                    )
                }
              }
          }
        }
      }
    } { ws =>
      if (ws.stopContainer()) logger.info("Container stopped successfully")
      else logger.error("Failed to stop the container")
    }

    result.fold(
      error => logger.error("Error during workspace demo", error),
      _ => logger.info("Workspace tool demo completed successfully")
    )
  }

  /** Runs the prompt as an agent with the workspace tools, and logs its answer. */
  private def run(client: LLMClient, tools: ToolSet): Unit =
    Agent
      .builder("workspace-demo", client)
      .withSystemPrompt("You are a helpful assistant with workspace tools. Use them to help the user.")
      .withTools(tools)
      .withMaxSteps(10)
      .build()
      .flatMap(_.run(Prompt)) match {
      case Right(result) =>
        logger.info("Answer: {}", result.answer.getOrElse(s"(no answer; status ${result.status})"))
        logger.info("Tokens used: {}", result.usage)
      case Left(error) => logger.error("Error occurred: {}", error.formatted)
    }
}
