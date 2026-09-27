package org.llm4s.samples.toolapi

import org.llm4s.config.Llm4sConfig
import org.llm4s.codegen.WorkspaceConfigSupport
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.toolapi._
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory
import upickle.default._

import scala.annotation.tailrec
import scala.util.{ Try, Using }

/**
 * Example demonstrating how to use workspace tools with different LLM models.
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

  // Define result types for workspace tools
  case class ExploreResult(files: List[String], directories: List[String])
  case class ReadResult(content: String, lines: Int)
  case class SearchResult(matches: List[String], totalMatches: Int)
  case class ExecuteResult(output: String, exitCode: Int)
  case class CommandResult(message: String, success: Boolean)

  // Implicit readers/writers for JSON serialization
  implicit val exploreResultRW: ReadWriter[ExploreResult] = macroRW
  implicit val readResultRW: ReadWriter[ReadResult]       = macroRW
  implicit val searchResultRW: ReadWriter[SearchResult]   = macroRW
  implicit val executeResultRW: ReadWriter[ExecuteResult] = macroRW
  implicit val commandResultRW: ReadWriter[CommandResult] = macroRW

  /** Named provider sections run after the default one when no section names are given as arguments. */
  val DefaultComparisonSections: Seq[String] = Seq("openai-main", "anthropic-main")

  def main(args: Array[String]): Unit = {
    val comparisonSections = if (args.nonEmpty) args.toSeq else DefaultComparisonSections

    // Typed workspace settings
    val ws =
      WorkspaceConfigSupport
        .load()
        .fold(
          err => throw new IllegalArgumentException(s"Failed to load workspace settings: ${err.formatted}"),
          identity
        )
    logger.info(s"Using workspace directory: ${ws.workspaceDir}")

    // Create a workspace
    val workspace = new ContainerisedWorkspace(ws.workspaceDir, ws.imageName, ws.hostPort)

    val result = Using.resource(workspace) { ws =>
      Try {
        // Start the workspace container
        if (ws.startContainer()) {
          logger.info("Container started successfully")

          // Create test file for demonstration
          ws.writeFile(
            "/workspace/test_file.txt",
            "This is a test file\nIt has multiple lines\nFor testing search functionality"
          )
          ws.writeFile("/workspace/another_file.txt", "This is another test file\nWith different content")

          // Create the workspace tools
          WorkspaceTools.createDefaultWorkspaceTools(ws) match {
            case Left(err) =>
              logger.error("Failed to create workspace tools: {}", err.formatted)

            case Right(workspaceTools) =>
              val toolRegistry       = new ToolRegistry(workspaceTools)
              val registryServiceRes = Llm4sConfig.modelRegistryService()

              // Create test prompt for the LLM
              val prompt = "You are a helpful assistant that has access to a workspace with files. " +
                "Please explore the /workspace directory, then read the content of test_file.txt, " +
                "and search for the word 'test' across all files. " +
                "Return a summary of what you found."

              // Optional: run with the default named provider from configuration (llm4s.providers.provider)
              logger.info("Attempting the default provider from configuration (llm4s.providers.provider)...")
              val activeClientRes = registryServiceRes.flatMap { registryService =>
                given org.llm4s.model.ModelRegistryService = registryService
                Llm4sConfig.defaultProvider().flatMap { provCfg =>
                  logger.info(s"Testing with active model: ${provCfg.model}")
                  LLMConnect.getClient(provCfg).map(client => (client, provCfg.model))
                }
              }
              activeClientRes match {
                case Right((client, model)) =>
                  logger.info(s"Running workspace tool demo with active model: $model")
                  testLLMWithTools(client, toolRegistry, prompt)
                case Left(err) =>
                  logger.warn(s"Active provider client setup skipped: ${err.formatted}")
              }

              // Then run with each named section asked for - by default an OpenAI and an Anthropic one
              comparisonSections.foreach { sectionName =>
                logger.info(s"Testing with named provider section '$sectionName'...")
                val clientRes = registryServiceRes.flatMap { registryService =>
                  given org.llm4s.model.ModelRegistryService = registryService
                  Llm4sConfig.provider(sectionName).flatMap { provCfg =>
                    logger.info(s"Section '$sectionName': ${provCfg.providerId.asString} / ${provCfg.model}")
                    LLMConnect.getClient(provCfg)
                  }
                }
                clientRes match {
                  case Right(client) =>
                    testLLMWithTools(client, toolRegistry, prompt)
                  case Left(err) =>
                    logger.error(s"Section '$sectionName' skipped: ${err.formatted}")
                    logger.info(
                      s"Add a '$sectionName' section under llm4s.providers in the samples' application.local.conf - " +
                        "see docs/getting-started/configuration.md#running-the-samples"
                    )
                }
              }
          }
        } else {
          logger.error("Failed to start the workspace container")
        }
      }
    } { ws =>
      // Always clean up the container
      if (ws.stopContainer()) {
        logger.info("Container stopped successfully")
      } else {
        logger.error("Failed to stop the container")
      }
    }

    result.fold(
      error => logger.error("Error during workspace demo", error),
      _ => logger.info("Workspace tool demo completed successfully")
    )
  }

  /**
   * Test an LLM with the workspace tools
   */
  private def testLLMWithTools(client: LLMClient, toolRegistry: ToolRegistry, prompt: String): Unit = {
    // Create initial conversation with system and user messages
    val initialConversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant with workspace tools. Use them to help the user."),
        UserMessage(prompt)
      )
    )

    // Create completion options with tools
    val options = CompletionOptions(
      tools = toolRegistry.tools
    )

    logger.info("Sending request to LLM with workspace tools...")

    // Start the conversation loop
    processLLMRequest(client, initialConversation, options, toolRegistry)
  }

  /**
   * Process an LLM request, handling any tool calls recursively
   */
  @tailrec
  private def processLLMRequest(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions,
    toolRegistry: ToolRegistry,
    depth: Int = 0
  ): Unit =
    client.complete(conversation, options) match {
      case Right(completion) =>
        val assistantMessage = completion.message

        logger.info("LLM Response (Step {}): {}", depth + 1, assistantMessage.content)

        // Check if there are tool calls
        if (assistantMessage.toolCalls.nonEmpty) {
          logger.info("Processing {} tool calls", assistantMessage.toolCalls.length)

          // Process each tool call and create tool messages
          val toolMessages = processToolCalls(assistantMessage.toolCalls, toolRegistry)

          // Create updated conversation with assistant message and tool responses
          val updatedConversation = conversation
            .addMessage(assistantMessage)
            .addMessages(toolMessages)

          logger.info("Sending follow-up request with tool results")

          // Make the follow-up API request
          processLLMRequest(client, updatedConversation, options, toolRegistry, depth + 1)
        } else {
          // If no tool calls, we're done
          logger.info("Final response received (no more tool calls)")

          // Print token usage if available
          completion.usage.foreach { usage =>
            logger.info(
              "Tokens used: {} ({} prompt, {} completion)",
              usage.totalTokens,
              usage.promptTokens,
              usage.completionTokens
            )
          }
        }

      case Left(error) =>
        logger.error("Error occurred: {}", error.formatted)
    }

  /**
   * Process tool calls and return tool messages with the results
   */
  private def processToolCalls(toolCalls: Seq[ToolCall], toolRegistry: ToolRegistry): Seq[ToolMessage] =
    toolCalls.map { toolCall =>
      val startTime = System.currentTimeMillis()

      logger.info("Executing tool: {} with arguments: {}", toolCall.name, toolCall.arguments)

      val request    = ToolCallRequest(toolCall.name, toolCall.arguments)
      val toolResult = toolRegistry.execute(request)

      val endTime  = System.currentTimeMillis()
      val duration = endTime - startTime

      val resultContent = toolResult match {
        case Right(json) =>
          val formatted = json.render(indent = 2)
          logger.info("Tool {} completed successfully in {}ms. Result: {}", toolCall.name, duration, formatted)
          formatted
        case Left(error) =>
          val errorJson = s"""{ "isError": true, "message": "$error" }"""
          logger.warn("Tool {} failed in {}ms with error: {}", toolCall.name, duration, error)
          errorJson
      }

      // Create a tool message with the result
      ToolMessage(toolCall.id, resultContent)
    }
}
