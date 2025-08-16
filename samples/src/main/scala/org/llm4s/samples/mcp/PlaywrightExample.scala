package org.llm4s.samples.mcp

import cats.implicits._
import org.llm4s.agent.{Agent, AgentState}
import org.llm4s.llmconnect.model.{Conversation, SystemMessage, UserMessage}
import org.llm4s.llmconnect.{LLM, LLMClient}
import org.llm4s.mcp._
import org.llm4s.toolapi.ToolFunction
import org.slf4j.LoggerFactory

import scala.util.Try
/**
 * This example shows how an LLM agent can use the Playwright MCP server
 * to perform browser automation tasks like navigation, clicking, and data extraction.
 *
 * Prerequisites:
 * 1. Node.js installed (required for npx)
 * 2. Internet connection - The example automatically downloads @playwright/mcp using npx
 *
 * The example will automatically launch the Playwright MCP server using npx.
 * In order for the example to complete successfully, do not interfere with the agent's navigation.
 * Run with: sbt "samples/runMain org.llm4s.samples.mcp.PlaywrightExample"
 *
 * ## Output:
 * - Console logs showing agent execution
 * - Trace files: playwright-agent-query-*.md with detailed tool usage
 */

object PlaywrightExample {
  private val logger = LoggerFactory.getLogger(getClass)

  private def logNoToolsError(): Unit = {
    logger.error(
      """
        |❌ No tools available from Playwright MCP server
        |This could indicate:
        |  1. The MCP server failed to start
        |  2. Network issues preventing package download
        |  3. Node.js or npx not properly installed
        |""".stripMargin)
  }

  private def logToolsInfo(tools: Seq[ToolFunction[?, ?]]): Unit = {
    logger.info("📦 Available Playwright tools ({} total):", tools.size)
    tools.zipWithIndex.foreach { case (tool, index) =>
      logger.info("   {}. {}: {}", index + 1, tool.name, tool.description)
    }
  }

  private def checkForTools(mcpRegistry: MCPToolRegistry): Either[String, Seq[ToolFunction[?, ?]]] = {
    val allTools = mcpRegistry.getAllTools
    val result = Either.cond(allTools.nonEmpty,
      allTools,
      "No tools available from Playwright MCP server")
    result.bimap (_ => logNoToolsError(), tools => logToolsInfo(tools))
    result
  }

  private def runQueries(mcpClient: LLMClient, mcpRegistry: MCPToolRegistry): Either[String, Unit] = Try {
    runBrowserAutomationQueries(mcpClient, mcpRegistry)
  }.toEither.leftMap(_.getMessage)

  private def closeMCPClient(mcpToolRegistry: MCPToolRegistry) = Try {
    logger.info("🧹 Cleaning up MCP connections...")
    mcpToolRegistry.closeMCPClients()
  }.toEither.leftMap(_.getMessage)

  def main(args: Array[String]): Unit = {
    logger.info("🚀 Playwright MCP Agent Example")
    logger.info("🌐 Demonstrating browser automation with MCP integration")

    val result = for {
      client <- validatePrerequisites()
      mcpRegistry = MCPToolRegistry()
      _ <- checkForTools(mcpRegistry)
      _ <- runQueries(client, mcpRegistry)
      _ <- closeMCPClient(mcpRegistry)
    } yield ()

    result.bimap(
      e => logger.error("💥 Unexpected error: {}", e),
      _ => logger.info("✨ Browser automation example completed successfully!")
    )
  }
  
  // Validate that prerequisites are installed
  private def checkCommand(cmd: String, toolName: String): Either[String, Unit] =
    Try {
      val process = new ProcessBuilder(cmd, "--version").start()
      val exited  = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
      if (!exited) {
        throw new RuntimeException(s"$toolName process did not complete in time")
      }
      if (process.exitValue() != 0) {
        throw new RuntimeException(s"$toolName is not installed or not accessible")
      }
    }.toEither.leftMap(ex => ex.getMessage)

  private def validatePrerequisites(): Either[String, LLMClient] =
    for {
      _ <- checkCommand("node", "Node.js")
      _ <- checkCommand("npx", "npx")
    } yield {
      logger.info("✅ Prerequisites validated and LLM client initialized")
      LLM.client()
    }

  // Run multiple browser automation queries to test different capabilities
  private def runBrowserAutomationQueries(client: org.llm4s.llmconnect.LLMClient, registry: MCPToolRegistry): Unit = {
    val agent = new Agent(client)

    // Define browser automation test queries
    // These are designed to test common web automation tasks with Playwright MCP
    val queries = Seq(
      "Navigate to https://example.com and tell me what the main heading says",
      "Go to https://httpbin.org/get and extract the information about the request that was made",
      "Visit https://www.wikipedia.org and find the search box, then tell me what placeholder text it has",
      "Navigate to https://github.com and identify what navigation menu items are available in the header"
    )

    // Execute each query with full tracing
    queries.zipWithIndex.foreach { case (query, index) =>
      val queryNum  = index + 1
      val traceFile = s"playwright-agent-query-$queryNum.md"

      logger.info("=" * 60)
      logger.info("🎯 Query {}: {}", queryNum, query)
      logger.info("📝 Trace: {}", traceFile)
      logger.info("=" * 60)

      // Run the agent with comprehensive error handling
      logger.info("🤖 Starting agent execution...")

      val systemPrompt = Some(
        """You are a browser automation assistant using Playwright. 
        |Your task is to help users navigate websites and extract information.
        |Always be specific about what you find on the page and provide clear, detailed responses.
        |If you encounter any issues, explain what happened and suggest alternatives.""".stripMargin
      )

      // Create custom initial state with the browser automation system prompt
      val initialMessages = Seq(
        SystemMessage(systemPrompt.getOrElse("You are a helpful assistant with access to tools.")),
        UserMessage(query)
      )
      val initialState = AgentState(
        conversation = Conversation(initialMessages),
        tools = registry,
        userQuery = query
      )

      // Use the agent's runUntilCompletion method directly with our custom state
      runAgentWithCustomPrompt(agent, initialState, Some(15), Some(traceFile)) match {
        case Right(finalState) =>
          logger.info("✅ Query {} completed: {}", queryNum, finalState.status)

          // Show final answer
          finalState.conversation.messages.reverse
            .find(_.role == "assistant")
            .fold {
              logger.warn("❌ No final answer found")
            } { msg =>
              logger.info("💬 Final Answer:")
              logger.info(msg.content)
            }

          // Show execution summary
          logger.info("📊 Summary:")
          logger.info("   Status: {}", finalState.status)
          logger.info("   Steps: {}", finalState.logs.size)
          logger.info("   Messages: {}", finalState.conversation.messages.size)
          logger.info("   For detailed tool usage, see trace file: {}", traceFile)

        case Left(err) =>
          logger.error("❌ Query {} failed: {}", queryNum, err)

          // Try to provide helpful debugging information
          if (err.toString.contains("No tools available")) {
            logger.info("💡 Tip: This could mean the MCP server isn't responding properly")
          } else if (err.toString.contains("timeout")) {
            logger.info("💡 Tip: The browser operation might be taking too long - try a simpler query")
          } else if (err.toString.contains("connection")) {
            logger.info("💡 Tip: Check your internet connection and that the MCP server is running")
          }
      }

      // Pause between queries to avoid overwhelming the browser
      if (queryNum < queries.size) {
        logger.info("⏳ Waiting before next query...")
        Thread.sleep(3000)
      }
    }

  }

  // Helper method to run agent with custom initial state
  private def runAgentWithCustomPrompt(
    agent: Agent,
    initialState: AgentState,
    maxSteps: Option[Int],
    traceLogPath: Option[String]
  ) = {
    import org.llm4s.error.LLMError
    import org.llm4s.types.Result

    import scala.annotation.tailrec

    // Write initial state if tracing is enabled
    traceLogPath.foreach(path => agent.writeTraceLog(initialState, path))

    @tailrec
    def runUntilCompletion(state: AgentState, stepsRemaining: Option[Int] = maxSteps): Result[AgentState] =
      (state.status, stepsRemaining) match {
        case (s, Some(0))
            if s == org.llm4s.agent.AgentStatus.InProgress || s == org.llm4s.agent.AgentStatus.WaitingForTools =>
          val updatedState = state
            .log("[system] Step limit reached")
            .withStatus(org.llm4s.agent.AgentStatus.Failed("Maximum step limit reached"))
          traceLogPath.foreach(path => agent.writeTraceLog(updatedState, path))
          Right(updatedState)

        case (org.llm4s.agent.AgentStatus.InProgress | org.llm4s.agent.AgentStatus.WaitingForTools, _) =>
          agent.runStep(state) match {
            case Right(newState) =>
              traceLogPath.foreach(path => agent.writeTraceLog(newState, path))
              runUntilCompletion(newState, stepsRemaining.map(_ - 1))
            case Left(error) =>
              val failedState = state.withStatus(org.llm4s.agent.AgentStatus.Failed(error.toString))
              traceLogPath.foreach(path => agent.writeTraceLog(failedState, path))
              Left(error)
          }

        case (org.llm4s.agent.AgentStatus.Complete, _) | (org.llm4s.agent.AgentStatus.Failed(_), _) =>
          Right(state)
      }

    runUntilCompletion(initialState)
  }

}
