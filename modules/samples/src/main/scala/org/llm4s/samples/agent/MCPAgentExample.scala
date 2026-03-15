package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentContext }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole.Assistant
import org.llm4s.mcp._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.Using

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

    val startTime = System.currentTimeMillis()
    val agentState = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      agent = new Agent(client)
      query = "Convert 100 USD to EUR and then check the weather in Paris"
      _     = logger.info(s"🎯 Running agent query: $query")
      agentState <- Using.resource(new MCPToolRegistry(Seq(serverConfig), Seq(weatherTool), 10.minutes)) {
        mcpRegistry =>
          val allTools = mcpRegistry.getAllTools
          logger.info(s"📦 Available tools (${allTools.size} total):")
          allTools.zipWithIndex.foreach { case (tool, index) =>
            val source = if (tool.description == "Retrieves current weather for the given location.") "local" else "MCP"
            logger.info(s"   ${index + 1}. ${tool.name} ($source): ${tool.description}")
          }
          agent.run(
            query = query,
            tools = mcpRegistry,
            maxSteps = Some(5),
            context = AgentContext(traceLogPath = Some(".log/mcp-agent-example.md"))
          )
      }
    } yield agentState

    val duration = System.currentTimeMillis() - startTime
    agentState match {
      case Right(finalState) =>
        logger.info(s"✅ Query successfully completed in ${duration}ms")
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
        logger.info(s"❌ Query failed to completed in ${duration}ms")
        logger.error(s"❌ Query failed: $error")
    }

    logger.info("✨ MCP Agent Example completed!")
  }
}
