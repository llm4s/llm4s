package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.ConfigReader
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model.MessageRole.Assistant
import org.llm4s.mcp._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
 * Example demonstrating agent execution with MCP tool integration
 *
 * This example shows how an LLM agent can seamlessly use both local tools 
 * and remote MCP tools with automatic fallback between transport protocols.
 *
 * Start the MCPServer first: sbt "samples/runMain org.llm4s.samples.mcp.DemonstrationMCPServer"
 * Then run: sbt "samples/runMain org.llm4s.samples.agent.MCPAgentExample"
 */
object MCPAgentExample {
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    logger.info("🚀 MCP Agent Example - Agent with MCP Tools")
    
    // Set up MCP server configuration (automatically tries latest protocol with fallback)
    val serverConfig = MCPServerConfig.streamableHTTP(
      name = "mcp-tools-server", 
      url = "http://localhost:8080/mcp",
      timeout = 30.seconds
    )
    
    // Create tool registry combining local and MCP tools
    val localWeatherTool = WeatherTool.tool
    val mcpRegistry = new MCPToolRegistry(
      mcpServers = Seq(serverConfig),
      localTools = Seq(localWeatherTool),
      cacheTTL = 10.minutes
    )
    
    // Show available tools
    val allTools = mcpRegistry.getAllTools
    logger.info(s"📦 Available tools (${allTools.size} total):")
    allTools.zipWithIndex.foreach { case (tool, index) =>
      val source = if (tool.description == "Retrieves current weather for the given location.") "local" else "MCP"
      logger.info(s"   ${index + 1}. ${tool.name} ($source): ${tool.description}")
    }
    
    // Test with agent
    runAgentExample(mcpRegistry)(LLMConfig())
    
    // Clean up
    mcpRegistry.closeMCPClients()
    logger.info("✨ MCP Agent Example completed!")
  }
  
  private def runAgentExample(registry: MCPToolRegistry)(config:ConfigReader): Unit = {
    val client    = LLM.client(config)
    val agent = new Agent(client)
    
    val query = "Convert 100 USD to EUR and then check the weather in Paris"
    
    logger.info(s"🎯 Running agent query: $query")
    
    val startTime = System.currentTimeMillis()
    
    agent.run(
      query = query,
      tools = registry,
      maxSteps = Some(5),
      traceLogPath = Some("mcp-agent-example.md"),
      systemPromptAddition = None
    ) match {
      case Right(finalState) =>
        val duration = System.currentTimeMillis() - startTime
        logger.info(s"✅ Query completed in ${duration}ms")
        
        // Show final answer
        finalState.conversation.messages.findLast(_.role == Assistant) match {
          case Some(msg) =>
            logger.info(s"💬 Agent Response: ${msg.content}")
          case None => 
            logger.warn("❌ No final answer found")
        }
        
        // Show execution summary
        logger.info(s"📊 Summary: ${finalState.logs.size} execution steps")
        
      case Left(error) =>
        logger.error(s"❌ Query failed: $error")
    }
  }
}
