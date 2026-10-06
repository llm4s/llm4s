package org.llm4s.samples.mcp

import cats.implicits._
import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.mcp._
import org.llm4s.samples.util.AgentResults
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

  private def logNoToolsError(): Unit =
    logger.error("""
        |❌ No tools available from Playwright MCP server
        |This could indicate:
        |  1. The MCP server failed to start
        |  2. Network issues preventing package download
        |  3. Node.js or npx not properly installed
        |""".stripMargin)

  private def logToolsInfo(tools: Seq[ToolFunction[?, ?]]): Unit = {
    logger.info("📦 Available Playwright tools ({} total):", tools.size)
    tools.zipWithIndex.foreach { case (tool, index) =>
      logger.info("   {}. {}: {}", index + 1, tool.name, tool.description)
    }
  }

  private def checkForTools(mcpRegistry: MCPToolRegistry): Either[String, Seq[ToolFunction[?, ?]]] = {
    val allTools = mcpRegistry.getAllTools
    val result   = Either.cond(allTools.nonEmpty, allTools, "No tools available from Playwright MCP server")
    result.bimap(_ => logNoToolsError(), tools => logToolsInfo(tools))
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

  private def checkCommand(cmd: String, toolName: String): Either[String, Unit] =
    Try(new ProcessBuilder(cmd, "--version").start()).toEither
      .leftMap(ex => s"Failed to start $toolName check: ${ex.getMessage}")
      .flatMap { process =>
        val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) {
          Left(s"$toolName process did not complete in time")
        } else {
          val exitCode = process.exitValue()
          if (exitCode != 0) {
            Left(s"$toolName is not installed or not accessible")
          } else {
            Right(())
          }
        }
      }

  private def validatePrerequisites(): Either[String, LLMClient] =
    for {
      _               <- checkCommand("node", "Node.js")
      _               <- checkCommand("npx", "npx")
      registryService <- Llm4sConfig.modelRegistryService().leftMap(_.formatted)
      providerCfg     <- Llm4sConfig.defaultProvider().leftMap(_.formatted)
      client <- {
        given org.llm4s.model.ModelRegistryService = registryService
        LLMConnect.getClient(providerCfg).leftMap(_.formatted)
      }
    } yield {
      logger.info("✅ Prerequisites validated and LLM client initialized")
      client
    }

  // Run multiple browser automation queries to test different capabilities
  private def runBrowserAutomationQueries(client: org.llm4s.llmconnect.LLMClient, registry: MCPToolRegistry): Unit = {
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
      val queryNum = index + 1

      logger.info("=" * 60)
      logger.info("🎯 Query {}: {}", queryNum, query)
      logger.info("=" * 60)

      // Run the agent with comprehensive error handling
      logger.info("🤖 Starting agent execution...")

      val systemPrompt =
        """You are a browser automation assistant using Playwright.
        |Your task is to help users navigate websites and extract information.
        |Always be specific about what you find on the page and provide clear, detailed responses.
        |If you encounter any issues, explain what happened and suggest alternatives.""".stripMargin

      val run = Agent
        .builder("playwright-agent", client)
        .withTools(registry)
        .withSystemPrompt(systemPrompt)
        .withMaxSteps(15)
        .build()
        .flatMap(_.run(query))

      run match {
        case Right(finalState) =>
          finalState.status match {
            case AgentStatus.Completed(answer) =>
              logger.info("✅ Query {} completed", queryNum)
              logger.info("💬 Final Answer:")
              logger.info(answer)
            case other =>
              logger.warn("❌ Query {} ended without an answer: {}", queryNum, AgentResults.describe(other))
          }

          // Show execution summary
          logger.info("📊 Summary:")
          logger.info("   Status: {}", finalState.status)
          logger.info("   Messages: {}", finalState.messages.size)

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

}
