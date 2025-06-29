package org.llm4s.mcp

import org.llm4s.toolapi._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

// MCP-aware tool registry that integrates with the existing tool API
class MCPToolRegistry(
  mcpServers: Seq[MCPServerConfig],
  localTools: Seq[ToolFunction[_, _]] = Seq.empty,
  cacheTTL: Duration = 10.minutes
) extends ToolRegistry(localTools) {

  private val logger                              = LoggerFactory.getLogger(getClass)
  private var mcpClients: Map[String, MCPClient]  = Map.empty
  private var toolCache: Map[String, CachedTools] = Map.empty

  logger.info(s"Initializing MCPToolRegistry with ${mcpServers.size} MCP servers and ${localTools.size} local tools")
  logger.debug(s"Cache TTL set to ${cacheTTL}")

  // Override to return ALL tools (local + MCP)
  override def tools: Seq[ToolFunction[_, _]] = getAllTools

  // Execute tools, trying local first then MCP
  override def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] = {
    logger.debug(s"Executing tool request: ${request.functionName}")

    // Try local tools first
    super.execute(request) match {
      case Right(result) =>
        logger.debug(s"Tool ${request.functionName} executed successfully via local tools")
        Right(result)
      case Left(_) =>
        logger.debug(s"Tool ${request.functionName} not found in local tools, trying MCP tools")
        // If local tools fail, try MCP tools
        findMCPTool(request.functionName) match {
          case Some(tool) =>
            try {
              logger.debug(s"Executing MCP tool: ${request.functionName}")
              val result = tool.execute(request.arguments)
              logger.debug(s"MCP tool ${request.functionName} executed successfully")
              result
            } catch {
              case e: Exception =>
                logger.error(s"MCP tool ${request.functionName} execution failed", e)
                Left(ToolCallError.ExecutionError(e))
            }
          case None =>
            logger.warn(s"Tool ${request.functionName} not found in any registry (local or MCP)")
            Left(ToolCallError.UnknownFunction(request.functionName))
        }
    }
  }

  // Get tools in OpenAI format
  override def getOpenAITools(strict: Boolean = true): ujson.Arr =
    ujson.Arr.from(tools.map(_.toOpenAITool(strict)))

  // Get all tools (local + MCP)
  def getAllTools: Seq[ToolFunction[_, _]] = {
    val mcpTools = getAllMCPTools
    logger.debug(
      s"Total tools available: ${localTools.size} local + ${mcpTools.size} MCP = ${localTools.size + mcpTools.size}"
    )
    localTools ++ mcpTools
  }

  // Find a specific MCP tool by name
  private def findMCPTool(name: String): Option[ToolFunction[_, _]] = {
    logger.debug(s"Searching for MCP tool: $name")
    mcpServers.view
      .flatMap(server => getToolsFromServer(server).find(_.name == name))
      .headOption
  }

  // Get all MCP tools from all servers
  private def getAllMCPTools: Seq[ToolFunction[_, _]] =
    mcpServers.flatMap(getToolsFromServer)

  // Get tools from a specific server (with caching)
  private def getToolsFromServer(server: MCPServerConfig): Seq[ToolFunction[_, _]] = {
    val now = System.currentTimeMillis()
    toolCache.get(server.name) match {
      case Some(cached) if !cached.isExpired(now, cacheTTL) =>
        logger.debug(s"Cache hit for server ${server.name}, returning ${cached.tools.size} cached tools")
        cached.tools
      case Some(_) =>
        logger.debug(s"Cache expired for server ${server.name}, refreshing tools")
        refreshToolsFromServer(server, now)
      case None =>
        logger.debug(s"No cache entry for server ${server.name}, fetching tools")
        refreshToolsFromServer(server, now)
    }
  }

  // Refresh tools from server and update cache
  private def refreshToolsFromServer(server: MCPServerConfig, timestamp: Long): Seq[ToolFunction[_, _]] = {
    logger.info(s"Refreshing tools from MCP server: ${server.name}")
    Try {
      val client = getOrCreateClient(server)
      val tools  = client.getTools()
      toolCache = toolCache + (server.name -> CachedTools(tools, timestamp))
      logger.info(s"Successfully refreshed ${tools.size} tools from server ${server.name}")
      tools
    } match {
      case Success(tools) => tools
      case Failure(exception) =>
        logger.error(s"Failed to refresh tools from ${server.name}: ${exception.getMessage}", exception)
        Seq.empty
    }
  }

  // Get or create MCP client for a server
  private def getOrCreateClient(server: MCPServerConfig): MCPClient =
    mcpClients.getOrElse(
      server.name, {
        logger.info(s"Creating new MCP client for server: ${server.name}")
        val client = new MCPClientImpl(server)
        mcpClients = mcpClients + (server.name -> client)
        logger.debug(s"MCP client created successfully for server: ${server.name}")
        client
      }
    )

  // Utility methods for cache management
  def clearCache(): Unit = {
    logger.info("Clearing all tool caches")
    toolCache = Map.empty
  }

  // Refresh cache for all servers
  def refreshCache(): Unit = {
    logger.info(s"Refreshing cache for all ${mcpServers.size} MCP servers")
    val now = System.currentTimeMillis()
    mcpServers.foreach(server => refreshToolsFromServer(server, now))
    logger.info("Cache refresh completed for all servers")
  }

  // Close all MCP clients
  def closeMCPClients(): Unit = {
    logger.info(s"Closing ${mcpClients.size} MCP clients")
    mcpClients.values.foreach(_.close())
    mcpClients = Map.empty
    logger.info("All MCP clients closed")
  }

  // Initialize MCP tools after all methods are defined
  private def initializeMCPTools(): Unit = {
    logger.info("Initializing MCP tools for all configured servers")
    mcpServers.foreach(server => refreshToolsFromServer(server, System.currentTimeMillis()))
    logger.info("MCP tools initialization completed")
  }
  initializeMCPTools()
}

// Helper case class for cleaner caching
private case class CachedTools(tools: Seq[ToolFunction[_, _]], timestamp: Long) {
  def isExpired(now: Long, ttl: Duration): Boolean =
    (now - timestamp) >= ttl.toMillis
}
