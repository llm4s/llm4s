package org.llm4s.samples.assistant

import org.llm4s.assistant.AssistantAgent
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLM
import org.llm4s.mcp.{ MCPServerConfig, MCPToolRegistry }
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
 * Example demonstrating the interactive AssistantAgent with MCP integration
 * 
 * This example shows how to create an interactive assistant that combines:
 * - **Local tools** (WeatherTool) - Run in-process with the application
 * - **MCP tools** (Playwright for browser automation, Email client for Gmail) - Run as external processes via MCP protocol
 * - **Session management** with conversation persistence
 * - **Interactive terminal commands**
 * 
 * ## Prerequisites:
 * - Set environment variables: OPENAI_API_KEY or ANTHROPIC_API_KEY
 * - Node.js installed (required for Playwright MCP server via npx)
 * - Python 3.12+ and email credentials configured in .env file (for email MCP server)
 * - Internet connection (for downloading @playwright/mcp and email dependencies)
 * 
 * ## Email MCP Server Setup:
 * ```bash
 * git clone https://github.com/rorygraves/claude-post.git
 * cd claude-post && /opt/homebrew/bin/python3 -m venv .venv && pip install -e .
 * doSet environment variables with: EMAIL_ADDRESS=your@gmail.com, EMAIL_PASSWORD=app-password
 * ```
 * 
 * ## How to run:
 * ```bash
 * sbt "samples/runMain org.llm4s.samples.assistant.AssistantAgentExample"
 * ```
 * 
 * ## Interactive Commands:
 * - `/help` - Show available commands
 * - `/new` - Start a new session (saves current session)
 * - `/save [title]` - Save current session with optional title
 * - `/sessions` - List recent saved sessions
 * - `/quit` - Save session and exit
 * 
 * ## Usage Examples:
 * - "What's the weather in London?" (uses local WeatherTool)
 * - "Navigate to https://example.com and tell me the main heading" (uses Playwright MCP)
 * - "Send an email to john@example.com about the meeting" (uses Email MCP)
 * - "Check the weather in Paris, then visit Wikipedia and search for weather" (combines tools)
 * 
 * Sessions are automatically saved to `./sessions/` directory as markdown files.
 */
object AssistantAgentExample {
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    logger.info("Starting LLM4S Interactive Assistant Agent Example...")
    
    // Get LLM client from environment variables
    val client = LLM.client(LLMConfig())
    
    // Create MCP server configuration for Playwright
    val playwrightServerConfig = MCPServerConfig.stdio(
      name = "playwright-mcp-server",
      command = Seq("npx", "@playwright/mcp@latest"),
      timeout = 60.seconds
    )
    
    // Create MCP server configuration for Claude Post Email Server  
    val emailServerConfig = MCPServerConfig.stdio(
      name = "claude-post-email-server",
      command = Seq("bash", "-c", "cd claude-post && source .venv/bin/activate && arch -arm64 .venv/bin/email-client --enable-write-operations"),
      timeout = 60.seconds
    )
    
    // Create tool registry combining local and MCP tools
    val tools = new MCPToolRegistry(
      mcpServers = Seq(playwrightServerConfig, emailServerConfig),
      localTools = Seq(WeatherTool.tool),
      cacheTTL = 10.minutes
    )
    
    // Create the assistant agent
    val assistant = new AssistantAgent(
      client = client,
      tools = tools,
      sessionDir = "./sessions" // Sessions will be saved here
    )
    
    // Display available tools
    val allTools = tools.getAllTools
    logger.info("Available tools ({} total):", allTools.size)
    allTools.zipWithIndex.foreach { case (tool, index) =>
      val source = if (tool.name == "weather") "local" 
                  else if (tool.name.contains("gmail") || tool.name.contains("email")) "email-MCP"
                  else "playwright-MCP"
      logger.info("   {}. {} ({}): {}", index + 1, tool.name, source, tool.description)
    }
    
    // Start the interactive session
    logger.info("Launching interactive assistant session")
    assistant.startInteractiveSession() match {
      case Right(_) =>
        logger.info("Assistant session completed successfully")
        
      case Left(error) =>
        logger.error("Assistant session failed: {}", error)
        System.exit(1)
    }
    
    // Clean up MCP connections
    logger.info("Cleaning up MCP connections...")
    tools.closeMCPClients()
  }
}