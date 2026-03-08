package org.llm4s.samples.mcp

import org.llm4s.mcp._
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating basic MCP tool usage with automatic fallback
 *
 * This example shows how to use MCP tools directly via the tool registry.
 * The client automatically detects the best transport protocol with fallback.
 *
 * Start the MCPServer first: sbt "samples/runMain org.llm4s.samples.mcp.DemonstrationMCPServer"
 * Then run: sbt "samples/runMain org.llm4s.samples.mcp.MCPToolExample"
 */
object MCPToolExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("🚀 MCP Tool Example - Basic MCP Tool Usage")

    // Load the configuration from application.conf using shared MCPConfig
    val config = MCPConfig.loadClientConfig() match {
      case Right(conf) => conf
      case Left(failures) =>
        logger.error(s"Failed to load configuration: ${failures.prettyPrint()}")
        sys.exit(1)
    }

    // Create MCP server configuration using loaded settings
    val serverConfig = MCPServerConfig.streamableHTTP(
      name = config.name,
      url = s"http://localhost:${config.port}${config.path}",
      timeout = config.timeout
    )

    // Create local weather tool for comparison
    val localWeatherTool = WeatherTool.toolSafe match {
      case Right(tool) => tool
      case Left(err) =>
        logger.error("Failed to load weather tool: {}", err.formatted)
        sys.exit(1)
    }

    // Create MCP registry combining local and MCP tools using loaded settings
    val mcpRegistry = new MCPToolRegistry(
      mcpServers = Seq(serverConfig),
      localTools = Seq(localWeatherTool),
      cacheTTL = config.cacheTTL
    )

    // Show all available tools
    val allTools = mcpRegistry.getAllTools
    logger.info(s"📦 Available tools (${allTools.size} total):")
    allTools.zipWithIndex.foreach { case (tool, index) =>
      val source = if (tool.description.contains("local")) "local" else "MCP"
      logger.info(s"   ${index + 1}. ${tool.name} ($source): ${tool.description}")
    }
    logger.info("ℹ️  Note: Local tools take precedence over MCP tools with same name")

    // Test the tools
    runToolTests(mcpRegistry)

    // Clean up
    mcpRegistry.closeMCPClients()
    logger.info("✨ Example completed!")
  }

  private def runToolTests(registry: MCPToolRegistry): Unit = {
    logger.info("🧪 Testing MCP tools:")

    // Test 1: Weather tool (local version)
    logger.info("1️⃣ Testing local weather tool:")
    val localWeatherRequest = ToolCallRequest(
      functionName = "get_weather",
      arguments = ujson.Obj(
        "location" -> ujson.Str("London, UK"),
        "units"    -> ujson.Str("celsius")
      )
    )

    executeAndLog(registry, localWeatherRequest, "   ")

    // Test 2: Currency conversion (MCP tool)
    logger.info("2️⃣ Testing MCP currency conversion:")
    val currencyRequest = ToolCallRequest(
      functionName = "currency_convert",
      arguments = ujson.Obj(
        "amount"        -> ujson.Num(100),
        "from_currency" -> ujson.Str("USD"),
        "to_currency"   -> ujson.Str("EUR")
      )
    )

    executeAndLog(registry, currencyRequest, "   ")

    // Test 3: Weather tool (local tool will be used due to name conflict)
    logger.info("3️⃣ Testing weather tool (local tool takes precedence):")
    val mcpWeatherRequest = ToolCallRequest(
      functionName = "get_weather",
      arguments = ujson.Obj(
        "location" -> ujson.Str("Tokyo, Japan"),
        "units"    -> ujson.Str("celsius")
      )
    )

    executeAndLog(registry, mcpWeatherRequest, "   ")
  }

  private def executeAndLog(registry: MCPToolRegistry, request: ToolCallRequest, indent: String): Unit =
    registry.execute(request) match {
      case Right(result) =>
        logger.info(s"${indent}✅ Success: ${result.render()}")
      case Left(error) =>
        logger.error(s"${indent}❌ Failed: $error")
    }
}
