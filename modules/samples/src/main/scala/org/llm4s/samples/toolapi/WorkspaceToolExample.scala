package org.llm4s.samples.toolapi

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.{AnthropicConfig, OpenAIConfig}
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.toolapi._
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory
import upickle.default._

import scala.annotation.tailrec
import scala.util.{ Try, Using }
import org.llm4s.types.TryOps

/**
 * Example demonstrating how to use workspace tools with different LLM models
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

  def main(args: Array[String]): Unit = {
    val config = ConfigReader.LLMConfig().getOrElse(throw new IllegalArgumentException("Failed to read config"))
    val gpt4oModelName  = config.getOrElse("LLM_MODEL_GPT4O", "gpt-4o")
    val sonnetModelName = "claude-3-7-sonnet-latest"

    // TODO read from config
    // Sample workspace directory
    val workspaceDir = System.getProperty("user.home") + "/workspace-demo"
    logger.info(s"Using workspace directory: $workspaceDir")

    // Create a workspace
    val workspace = new ContainerisedWorkspace(workspaceDir)

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
          val workspaceTools = WorkspaceTools.createDefaultWorkspaceTools(ws)
          val toolRegistry   = new ToolRegistry(workspaceTools)

          // Create test prompt for the LLM
          val prompt = "You are a helpful assistant that has access to a workspace with files. " +
            "Please explore the /workspace directory, then read the content of test_file.txt, " +
            "and search for the word 'test' across all files. " +
            "Return a summary of what you found."

          // Test with GPT-4o
          logger.info(s"Testing with OpenAI's $gpt4oModelName...")
          val openaiClientRes = for {
            cfg    <- OpenAIConfig(gpt4oModelName, config)
            client <- LLMConnect.getClient(LLMProvider.OpenAI, cfg)
          } yield client
          openaiClientRes match {
            case Right(openaiClient) =>
              testLLMWithTools(openaiClient, toolRegistry, prompt)
            case Left(err) =>
              logger.error(s"OpenAI client setup failed: ${err.formatted}")
          }

          // Test with Claude
          logger.info(s"Testing with Anthropic's $sonnetModelName...")
          val anthropicClientRes = for {
            cfg    <- AnthropicConfig(sonnetModelName, config)
            client <- LLMConnect.getClient(LLMProvider.Anthropic, cfg)
          } yield client
          anthropicClientRes match {
            case Right(anthropicClient) =>
              testLLMWithTools(anthropicClient, toolRegistry, prompt)
            case Left(err) =>
              logger.error(s"Anthropic client setup failed: ${err.formatted}")
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

    result.toResult.fold(
      error => logger.error(s"Error during workspace demo: ${error.message}"),
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
