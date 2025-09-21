package org.llm4s.mcp

import cats.implicits._
import org.llm4s.toolapi._
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

// MCP-aware tool registry that integrates with the existing tool API
class MCPToolRegistry(
  mcpServers: Seq[MCPServerConfig],
  localTools: Seq[ToolFunction[_, _]] = Seq.empty,
  cacheTTL: Duration = 10.minutes
) extends ToolRegistry(localTools)
    with AutoCloseable {

  private val logger                                            = LoggerFactory.getLogger(getClass)
  private val mcpClients: ConcurrentHashMap[String, MCPClient]  = new ConcurrentHashMap()
  private val toolCache: ConcurrentHashMap[String, CachedTools] = new ConcurrentHashMap()

  logger.info(s"Initializing MCPToolRegistry with ${mcpServers.size} MCP servers and ${localTools.size} local tools")
  logger.debug(s"Cache TTL set to $cacheTTL")

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
            logger.debug(s"Executing MCP tool: ${request.functionName}")
            Try(tool.execute(request.arguments)).toEither.left.map { e =>
              logger.error(s"MCP tool ${request.functionName} execution failed", e)
              ToolCallError.ExecutionError(request.functionName, e)
            }.flatten
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
    Option(toolCache.get(server.name)) match {
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
    val result = for {
      client <- Try(getOrCreateClient(server)).toEither.leftMap { ex =>
        logger.trace("{}", ex.getStackTrace)
        ex.getMessage
      }
      tools <- client.getTools()
    } yield {
      logger.debug(s"Successfully refreshed ${tools.size} tools from server ${server.name}")
      toolCache.put(server.name, CachedTools(tools, timestamp))
      tools
    }
    result.left.foreach { errMsg =>
      logger.error("Failed to refresh tools from ${}: {}", server.name, errMsg)
      removeServerFromCache(server) // Clean up failed client
    }
    result.getOrElse(Seq.empty)
  }

  private def removeServerFromCache(server: MCPServerConfig): Unit =
    Option(mcpClients.remove(server.name)).foreach { failedClient =>
      Try(failedClient.close()).recover { case e =>
        logger.debug(s"Error closing failed client for ${server.name}: ${e.getMessage}")
      }
    }

  private def createMCPClient(server: MCPServerConfig): MCPClient = {
    logger.info(s"Creating new MCP client for server: ${server.name}")
    val client = new MCPClientImpl(server)
    logger.debug(s"MCP client created successfully for server: ${server.name}")
    client
  }

  // Get or create MCP client for a server (thread-safe)
  private def getOrCreateClient(server: MCPServerConfig): MCPClient =
    // Use computeIfAbsent for thread-safe client creation
    Option(mcpClients.get(server.name)).getOrElse(
      mcpClients.computeIfAbsent(server.name, _ => createMCPClient(server))
    )

  // Utility methods for cache management
  def clearCache(): Unit = {
    logger.info("Clearing all tool caches")
    toolCache.clear()
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
    mcpClients.values.asScala.foreach(_.close())
    mcpClients.clear()
    logger.info("All MCP clients closed")
  }

  // Health check for MCP servers
  def healthCheck(): Map[String, Boolean] = {
    logger.info("Performing health check on all MCP servers")
    val results = mcpServers.map { server =>
      val isHealthy = Try {
        val client = getOrCreateClient(server)
        client.initialize().isRight
      }.getOrElse(false)

      logger.debug(s"Health check for ${server.name}: ${if (isHealthy) "OK" else "FAILED"}")
      server.name -> isHealthy
    }.toMap

    val healthyCount = results.values.count(identity)
    logger.info(s"Health check completed: $healthyCount/${results.size} servers healthy")
    results
  }

  // Initialize MCP tools after all methods are defined (with lazy initialization option)
  private def initializeMCPTools(): Unit = {
    logger.info("Initializing MCP tools for all configured servers")

    // Initialize servers in parallel for better performance
    val results = mcpServers.map { server =>
      Try {
        refreshToolsFromServer(server, System.currentTimeMillis())
        server.name -> "success"
      }.recover { case e =>
        logger.warn(s"Failed to initialize server ${server.name}: ${e.getMessage}")
        server.name -> s"failed: ${e.getMessage}"
      }.get
    }

    val successCount = results.count(_._2 == "success")
    logger.info(s"MCP tools initialization completed: $successCount/${results.size} servers initialized successfully")

    if (successCount == 0) {
      logger.warn("No MCP servers were successfully initialized - all tools will be local only")
    }
  }

  // Initialize tools during construction
  initializeMCPTools()

  override def close(): Unit =
    closeMCPClients()
}

object MCPToolRegistry {
  // Create MCP server configuration for Playwright
  // Using stdio transport to launch and communicate with Playwright MCP server
  val mcpServerConfig = MCPServerConfig.stdio(
    name = "playwright-mcp-server",
    command = Seq("npx", "@playwright/mcp@latest"),
    timeout = 60.seconds
  )

  def apply() =
    new MCPToolRegistry(
      mcpServers = Seq(mcpServerConfig),
      localTools = Seq.empty, // No local tools, using only Playwright MCP tools
      cacheTTL = 10.minutes
    )
}

// Helper case class for cleaner caching
private case class CachedTools(tools: Seq[ToolFunction[_, _]], timestamp: Long) {
  def isExpired(now: Long, ttl: Duration): Boolean =
    (now - timestamp) >= ttl.toMillis
}
