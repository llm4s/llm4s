package org.llm4s.samples.toolapi

import org.llm4s.llmconnect.{ LLM, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config.{ AnthropicConfig, OpenAIConfig }
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.shared._
import org.llm4s.toolapi._
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import upickle.default._
import ujson._

/**
 * Example demonstrating how to use workspace tools with different LLM models
 * This example uses the OpenAIToolFix to ensure proper compatibility
 */
object FixedWorkspaceToolExample {
  private val logger = LoggerFactory.getLogger(getClass)

  // Define result types for workspace tools
  case class ExploreResult(files: List[String], directories: List[String])
  case class ReadResult(content: String, lines: Int)
  case class SearchResult(matches: List[String], totalMatches: Int)
  case class ExecuteResult(output: String, exitCode: Int)

  // Implicit readers/writers for JSON serialization
  implicit val exploreResultRW: ReadWriter[ExploreResult] = macroRW
  implicit val readResultRW: ReadWriter[ReadResult] = macroRW
  implicit val searchResultRW: ReadWriter[SearchResult] = macroRW
  implicit val executeResultRW: ReadWriter[ExecuteResult] = macroRW

  def main(args: Array[String]): Unit = {
    // Read LLM model names from environment variables
    val gpt4oModelName = sys.env.getOrElse("LLM_MODEL_GPT4O", "gpt-4o")
    val sonnetModelName = sys.env.getOrElse("LLM_MODEL_SONNET_37", "claude-3-sonnet-20240229")

    // Sample workspace directory
    val workspaceDir = System.getProperty("user.home") + "/workspace-demo"
    logger.info(s"Using workspace directory: $workspaceDir")

    // Create a workspace
    val workspace = new ContainerisedWorkspace(workspaceDir)

    try {
      // Start the workspace container
      if (workspace.startContainer()) {
        logger.info("Container started successfully")

        // Create test file for demonstration
        workspace.writeFile("/workspace/test_file.txt", "This is a test file\nIt has multiple lines\nFor testing search functionality")
        workspace.writeFile("/workspace/another_file.txt", "This is another test file\nWith different content")

        // Create the workspace tools with proper schemas for OpenAI
        val workspaceTools = createWorkspaceTools(workspace)
        val toolRegistry = new ToolRegistry(workspaceTools)

        // Create test prompt for the LLM
        val prompt = "You are a helpful assistant that has access to a workspace with files. " +
          "Please explore the /workspace directory, then read the content of test_file.txt, " +
          "and finally search for the word 'test' across all files. " +
          "Return a summary of what you found."

        // Test with GPT-4o
        logger.info(s"Testing with OpenAI's $gpt4oModelName...")
        val openaiConfig = OpenAIConfig(
          apiKey = sys.env.getOrElse("OPENAI_API_KEY", ""),
          model = gpt4oModelName
        )
        
        val openaiClient = LLM.client(LLMProvider.OpenAI, openaiConfig)
        testLLMWithTools(openaiClient, toolRegistry, prompt)

        // Test with Claude
        logger.info(s"Testing with Anthropic's $sonnetModelName...")
        val anthropicConfig = AnthropicConfig(
          apiKey = sys.env.getOrElse("ANTHROPIC_API_KEY", ""),
          model = sonnetModelName
        )
        
        val anthropicClient = LLM.client(LLMProvider.Anthropic, anthropicConfig)
        testLLMWithTools(anthropicClient, toolRegistry, prompt)
      } else {
        logger.error("Failed to start the workspace container")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error during workspace demo: ${e.getMessage}", e)
    } finally {
      // Always clean up the container
      if (workspace.stopContainer()) {
        logger.info("Container stopped successfully")
      } else {
        logger.error("Failed to stop the container")
      }
    }
  }

  /**
   * Creates a list of tools that wrap workspace functionality
   * This example uses schemas that will work with OpenAI's requirements
   */
  private def createWorkspaceTools(workspace: ContainerisedWorkspace): Seq[ToolFunction[_, _]] = {
    // 1. Tool for exploring files - only require the path parameter
    val exploreSchema = Schema
      .`object`[Map[String, Any]]("Explore files parameters")
      .withProperty(
        Schema.property(
          "path",
          Schema.string("Path to explore in the workspace")
        )
      )
      .withProperty(
        Schema.property(
          "recursive",
          Schema.boolean("Whether to explore recursively"),
          required = false  // Mark as not required for OpenAI
        )
      )

    def exploreHandler(params: SafeParameterExtractor): Either[String, ExploreResult] =
      for {
        path <- params.getString("path")
        recursiveValue = params.getBoolean("recursive").toOption.getOrElse(false)
      } yield {
        val result = workspace.exploreFiles(path, recursive = Some(recursiveValue))
        val files = result.files.filter(!_.isDirectory).map(_.path)
        val dirs = result.files.filter(_.isDirectory).map(_.path)
        ExploreResult(files, dirs)
      }

    val exploreTool = ToolBuilder[Map[String, Any], ExploreResult](
      "explore_files",
      "Explore files and directories in the workspace",
      exploreSchema
    ).withHandler(exploreHandler).build()

    // 2. Tool for reading files
    val readSchema = Schema
      .`object`[Map[String, Any]]("Read file parameters")
      .withProperty(
        Schema.property(
          "path",
          Schema.string("Path to the file to read")
        )
      )
      .withProperty(
        Schema.property(
          "startLine",
          Schema.integer("Starting line (1-indexed)"),
          required = false
        )
      )
      .withProperty(
        Schema.property(
          "endLine",
          Schema.integer("Ending line (1-indexed)"),
          required = false
        )
      )

    def readHandler(params: SafeParameterExtractor): Either[String, ReadResult] =
      for {
        path <- params.getString("path")
        startLineOpt = params.getInt("startLine").toOption
        endLineOpt = params.getInt("endLine").toOption
      } yield {
        val result = workspace.readFile(path, startLineOpt, endLineOpt)
        ReadResult(result.content, result.totalLines)
      }

    val readTool = ToolBuilder[Map[String, Any], ReadResult](
      "read_file",
      "Read content from a file in the workspace",
      readSchema
    ).withHandler(readHandler).build()

    // 3. Tool for searching in files
    val searchSchema = Schema
      .`object`[Map[String, Any]]("Search files parameters")
      .withProperty(
        Schema.property(
          "query",
          Schema.string("Text to search for")
        )
      )
      .withProperty(
        Schema.property(
          "paths",
          Schema.array("List of paths to search in", Schema.string("Path to search in"))
        )
      )
      .withProperty(
        Schema.property(
          "searchType",
          Schema.string("Type of search (regex or literal)")
            .withEnum(Seq("regex", "literal"))
        )
      )
      .withProperty(
        Schema.property(
          "recursive",
          Schema.boolean("Whether to search recursively"),
          required = false
        )
      )

    def searchHandler(params: SafeParameterExtractor): Either[String, SearchResult] =
      for {
        query <- params.getString("query")
        pathsArr <- params.getArray("paths")
        pathsList = pathsArr.arr.map(_.str).toList
        searchType <- params.getString("searchType")
        recursiveValue = params.getBoolean("recursive").toOption.getOrElse(true)
      } yield {
        val result = workspace.searchFiles(pathsList, query, searchType, recursive = Some(recursiveValue))
        val matchStrings = result.matches.map(m => s"${m.path}:${m.line}: ${m.matchText}")
        SearchResult(matchStrings, result.totalMatches)
      }

    val searchTool = ToolBuilder[Map[String, Any], SearchResult](
      "search_files",
      "Search for content in files in the workspace",
      searchSchema
    ).withHandler(searchHandler).build()

    // 4. Tool for executing commands
    val executeSchema = Schema
      .`object`[Map[String, Any]]("Execute command parameters")
      .withProperty(
        Schema.property(
          "command",
          Schema.string("Command to execute")
        )
      )
      .withProperty(
        Schema.property(
          "workingDirectory",
          Schema.string("Working directory"),
          required = false
        )
      )

    def executeHandler(params: SafeParameterExtractor): Either[String, ExecuteResult] =
      for {
        command <- params.getString("command")
        workingDir = params.getString("workingDirectory").toOption
      } yield {
        val result = workspace.executeCommand(command, workingDir)
        ExecuteResult(result.stdout + "\n" + result.stderr, result.exitCode)
      }

    val executeTool = ToolBuilder[Map[String, Any], ExecuteResult](
      "execute_command",
      "Execute a command in the workspace",
      executeSchema
    ).withHandler(executeHandler).build()

    Seq(exploreTool, readTool, searchTool, executeTool)
  }

  /**
   * Test an LLM with the workspace tools
   * This includes the OpenAI tool fix
   */
  private def testLLMWithTools(client: LLMClient, toolRegistry: ToolRegistry, prompt: String): Unit = {
    // Create initial conversation with system and user messages
    val initialConversation = Conversation(Seq(
      SystemMessage("You are a helpful assistant with workspace tools. Use them to help the user."),
      UserMessage(prompt)
    ))

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

        println(s"\n===== LLM Response (Step ${depth + 1}) =====")
        println(assistantMessage.content)

        // Check if there are tool calls
        if (assistantMessage.toolCalls.nonEmpty) {
          println(s"\n===== Tool Calls (${assistantMessage.toolCalls.length}) =====")
          
          // Process each tool call and create tool messages
          val toolMessages = processToolCalls(assistantMessage.toolCalls, toolRegistry)

          // Create updated conversation with assistant message and tool responses
          val updatedConversation = conversation
            .addMessage(assistantMessage)
            .addMessages(toolMessages)

          println("\n===== Sending follow-up request with tool results =====")

          // Make the follow-up API request
          processLLMRequest(client, updatedConversation, options, toolRegistry, depth + 1)
        } else {
          // If no tool calls, we're done
          println("\n===== Final Response (no more tool calls) =====")

          // Print token usage if available
          completion.usage.foreach { usage =>
            println(
              s"\nTokens used: ${usage.totalTokens} (${usage.promptTokens} prompt, ${usage.completionTokens} completion)"
            )
          }
        }

      case Left(UnknownError(throwable)) =>
        println(s"Error: ${throwable.getMessage}")
        throwable.printStackTrace()
      case Left(error) =>
        println(s"Error: $error")
    }

  /**
   * Process tool calls and return tool messages with the results
   */
  private def processToolCalls(toolCalls: Seq[ToolCall], toolRegistry: ToolRegistry): Seq[ToolMessage] =
    toolCalls.map { toolCall =>
      println(s"\nExecuting tool: ${toolCall.name}")
      println(s"Arguments: ${toolCall.arguments}")

      val request = ToolCallRequest(toolCall.name, toolCall.arguments)
      val toolResult = toolRegistry.execute(request)

      val resultContent = toolResult match {
        case Right(json) => 
          val formatted = json.render(indent = 2)
          println(s"Tool execution result: $formatted")
          formatted
        case Left(error) => 
          val errorJson = s"""{ "isError": true, "message": "$error" }"""
          println(s"Tool execution error: $error")
          errorJson
      }

      // Create a tool message with the result
      ToolMessage(toolCall.id, resultContent)
    }
}