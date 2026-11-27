package org.llm4s.mcp

import cats.data.ValidatedNel
import cats.implicits._
import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.toolapi._
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

import scala.util.chaining.scalaUtilChainingOps

/**
 * `ToolRegistry` that transparently integrates local tools with tools
 * discovered from one or more MCP servers.
 *
 * == Tool lookup order ==
 * `execute` tries local tools first.  Only when a local tool with the
 * requested name is not found does it query MCP servers.  A tool defined
 * locally shadows any MCP tool with the same name.
 *
 * == Caching ==
 * Tool lists fetched from each MCP server are cached for `cacheTTL`
 * (default 10 minutes).  A stale cache entry is refreshed lazily on the
 * next call that requires that server's tools.  Call `clearCache()` or
 * `refreshCache()` to invalidate proactively.
 *
 * == Startup initialisation ==
 * When `initializeOnStartup` is `true` (the default), the registry fetches
 * and caches tools from all configured MCP servers during construction.
 * Set it to `false` in tests to defer or skip network calls.
 *
 * == Error handling ==
 * If an MCP server cannot be reached or returns an error, the failure is
 * logged and that server contributes no tools — the registry never throws during tool lookup.  Its
 * client is closed and evicted, and its cached tools and hints are dropped with it, because those
 * tools call through that client: the model is never offered a tool that cannot be called.  The next
 * lookup attempts a fresh connection.  A refresh that is only cancelled (the thread was interrupted)
 * keeps the client and the tools already fetched.
 *
 * == Reconnects ==
 * A tool the registry hands out finds its server's current tool when it is called, so one an agent
 * took when it was built (`AgentBuilder.withTools(registry)`) follows a reconnect, a `closeMCPClients()`
 * and the cache TTL. A lost session or a restarted server is recovered by the client itself, which
 * runs the handshake again (see [[MCPClientImpl]]).
 *
 * == Lifecycle ==
 * Implements `AutoCloseable`; call `close()` (or use `Using.resource`) to
 * shut down all active MCP transport connections.
 *
 * @param mcpServers         MCP server configurations to query for remote tools
 * @param localTools         Tools provided directly without a remote server call
 * @param cacheTTL           How long a server's tool list is considered fresh; defaults to 10 minutes
 * @param initializeOnStartup When `false`, MCP connections are deferred until first use
 */
class MCPToolRegistry(
  mcpServers: Seq[MCPServerConfig],
  localTools: Seq[ToolFunction[_]] = Seq.empty,
  cacheTTL: FiniteDuration = 10.minutes,
  initializeOnStartup: Boolean = true
) extends ToolRegistry
    with AutoCloseable {

  private val logger                                            = LoggerFactory.getLogger(getClass)
  private val mcpClients: ConcurrentHashMap[String, MCPClient]  = new ConcurrentHashMap()
  private val toolCache: ConcurrentHashMap[String, CachedTools] = new ConcurrentHashMap()

  logger.info(s"Initializing MCPToolRegistry with ${mcpServers.size} MCP servers and ${localTools.size} local tools")
  if (!initializeOnStartup) {
    logger.debug("Skipping MCP server initialization on startup (initializeOnStartup = false)")
  }
  logger.debug(s"Cache TTL set to $cacheTTL")

  /** Every tool, local and MCP, each name once; see [[getAllTools]]. */
  override def tools: Seq[ToolFunction[_]] = getAllTools

  /**
   * Runs a local tool when one has the name, otherwise the first configured MCP server's tool of that name.
   * A local tool's own failure is returned as it is: it is never retried against an MCP server.
   */
  override def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] = {
    logger.debug(s"Executing tool request: ${request.functionName}")
    localTools.find(_.name == request.functionName) match {
      case Some(local) =>
        logger.debug(s"Executing local tool: ${request.functionName}")
        ToolRegistry.run(local, request)
      case None =>
        findMCPTool(request.functionName) match {
          case Some(tool) =>
            logger.debug(s"Executing MCP tool: ${request.functionName}")
            // The same rule as for a local tool (design section 4.4): a call that ends while its thread
            // is interrupted is cancelled, whatever it returned, and is never reported as a failed tool.
            val outcome = ToolRegistry.run(tool, request)
            outcome match {
              case Left(ToolCallError.ExecutionError(_, e)) =>
                logger.error(s"MCP tool ${request.functionName} execution failed", e)
              case _ => ()
            }
            outcome
          case None if Thread.currentThread().isInterrupted =>
            // Finding the tool meant asking a server, and that was interrupted: not "no such tool".
            Left(ToolCallError.Cancelled(request.functionName))
          case None =>
            logger.warn(s"Tool ${request.functionName} not found in any registry (local or MCP)")
            Left(ToolCallError.UnknownFunction(request.functionName, getAllTools.map(_.name)))
        }
    }
  }

  /**
   * The hints declared, through its tool annotations, by the MCP server whose tool `name` is: the first
   * configured server that advertises the name, the same one [[execute]] runs (#3068). Its tools are discovered
   * first if they are not cached or are stale, as for `execute`. `None` for a tool no MCP server advertises, for
   * one a local tool shadows, and for any tool of a server whose last discovery failed. A later server's hints
   * for the same name never apply: they describe a different tool.
   *
   * '''Only a trusted server has hints.''' The MCP specification requires annotations from an untrusted
   * server to be treated as untrusted, and `ApprovalMiddleware.unlessReadOnly` skips approval for a tool
   * whose hints say read-only, so a server's own claim must not relax approval by default. A server
   * configured with `trustAnnotations = false` (the default, see [[MCPServerConfig]]) yields `None` for
   * every one of its tools; pass `registry.toolHints(name).getOrElse(ToolHints.default)` to
   * `AgentTool.fromToolFunction` and an untrusted tool keeps the conservative defaults, which require
   * approval. See [[MCPClient.getToolHints]].
   */
  def toolHints(name: String): Option[ToolHints] =
    if (localTools.exists(_.name == name)) None
    else
      mcpServers
        .find(server => getToolsFromServer(server).exists(_.name == name))
        .flatMap(server => Option(mcpClients.get(server.name)).flatMap(_.getToolHints().get(name)))

  /**
   * All tools, local first, then each server's in configuration order. A name is listed once: a local tool
   * shadows an MCP tool of the same name, and an earlier server's tool a later server's, which is also the
   * tool [[execute]] runs.
   */
  def getAllTools: Seq[ToolFunction[_]] = {
    val perServer = mcpServers.map(server => server.name -> getToolsFromServer(server))
    warnOnSharedNames(perServer)
    val mcpTools = perServer.flatMap(_._2)
    val all      = (localTools ++ mcpTools).distinctBy(_.name)
    logger.debug(
      s"Total tools available: ${localTools.size} local + ${mcpTools.size} MCP = ${all.size} distinct names"
    )
    all
  }

  // Find a specific MCP tool by name
  private def findMCPTool(name: String): Option[ToolFunction[_]] = {
    logger.debug(s"Searching for MCP tool: $name")
    mcpServers.view
      .flatMap(server => getToolsFromServer(server).find(_.name == name))
      .headOption
  }

  private val warnedSharedNames = ConcurrentHashMap.newKeySet[String]()

  // A name two sources advertise is one tool to the model: the first source's (#3068). WARN once per name and set
  // of sources, naming them, since the shadowed tool can never be called and its hints never apply.
  private def warnOnSharedNames(perServer: Seq[(String, Seq[ToolFunction[_]])]): Unit = {
    val sources = localTools.map(_.name -> "a local tool") ++
      perServer.flatMap { case (server, tools) => tools.map(_.name -> s"MCP server '$server'") }
    sources.groupBy(_._1).foreach { case (name, entries) =>
      val owners = entries.map(_._2).distinct
      if (owners.size > 1 && warnedSharedNames.add((name +: owners).mkString("\u0000"))) {
        logger.warn(
          s"Tool '$name' is advertised by ${owners.mkString(", ")}: ${owners.head}'s is the one called, and only its hints apply"
        )
      }
    }
  }

  // Get tools from a specific server (with caching)
  private def getToolsFromServer(server: MCPServerConfig): Seq[ToolFunction[_]] =
    currentEntry(server).map(_.tools).getOrElse(Seq.empty)

  // The server's cache entry, refreshed first when it is stale or belongs to a client the registry no longer holds
  // (closed by closeMCPClients, or evicted after a failure): its tools call through that client.
  private def currentEntry(server: MCPServerConfig): Option[CachedTools] = {
    val now = System.currentTimeMillis()
    Option(toolCache.get(server.name)) match {
      case Some(cached) if !cached.isExpired(now, cacheTTL) && (mcpClients.get(server.name) eq cached.client) =>
        logger.debug(s"Cache hit for server ${server.name}, returning ${cached.tools.size} cached tools")
        Some(cached)
      case Some(_) =>
        logger.debug(s"Cache for server ${server.name} is stale or its client was closed, refreshing tools")
        refreshToolsFromServer(server, now): Unit
        Option(toolCache.get(server.name))
      case None =>
        logger.debug(s"No cache entry for server ${server.name}, fetching tools")
        refreshToolsFromServer(server, now): Unit
        Option(toolCache.get(server.name))
    }
  }

  /**
   * `tool` as the registry hands it out (#3072): its handler finds the server's tool of that name when it is
   * called, through [[currentEntry]], so a tool an agent took when it was built follows a reconnect and the cache
   * TTL instead of calling through the client of that moment for good.
   */
  private def followingReconnects[R](server: MCPServerConfig, tool: ToolFunction[R]): ToolFunction[R] =
    tool.withHandler { params =>
      currentEntry(server).flatMap(_.raw.find(_.name == tool.name)) match {
        // The same server's tool of the same name, from the same client implementation: the same result type.
        case Some(current) => current.asInstanceOf[ToolFunction[R]].handler(params)
        case None =>
          Left(ToolHandlerError.failed(s"MCP server ${server.name} no longer offers the tool ${tool.name}"))
      }
    }

  // Refresh tools from server and update cache
  private def refreshToolsFromServer(server: MCPServerConfig, timestamp: Long): Seq[ToolFunction[_]] = {
    logger.info(s"Refreshing tools from MCP server: ${server.name}")
    val result = for {
      client <- Try(getOrCreateClient(server)).toEither.leftMap { ex =>
        logger.trace("{}", ex.getStackTrace)
        SimpleError(ex.getMessage): LLMError
      }
      raw <- client.getTools()
    } yield {
      logger.debug(s"Successfully refreshed ${raw.size} tools from server ${server.name}")
      val tools = raw.map(tool => followingReconnects(server, tool))
      toolCache.put(server.name, CachedTools(tools, raw, client, timestamp))
      tools
    }
    result.left.foreach {
      // An interrupted refresh is not a failed server: keep the client, cache nothing, report nothing.
      case _: CancelledError => logger.debug("Refreshing tools from {} was cancelled", server.name)
      case error =>
        logger.error("Failed to refresh tools from ${}: {}", server.name, error.message)
        // Closing the failed client ends the connection the cached tools call through, so those tools can no
        // longer be called: drop them with the client. Advertising them would have the model call tools that
        // are certain to fail. The next lookup reconnects and fetches the list again.
        toolCache.remove(server.name)
        removeServerFromCache(server)
    }
    // A cancelled refresh leaves the client open, so the tools fetched before it are still callable.
    result.getOrElse(Option(toolCache.get(server.name)).map(_.tools).getOrElse(Seq.empty))
  }

  private def removeServerFromCache(server: MCPServerConfig): Unit =
    Option(mcpClients.remove(server.name)).foreach { failedClient =>
      Try(failedClient.close()).recover { case e =>
        logger.debug(s"Error closing failed client for ${server.name}: ${e.getMessage}")
      }
    }

  // `private[mcp]` so a spec in this package can hand the registry a stub client.
  private[mcp] def createMCPClient(server: MCPServerConfig): MCPClient = {
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

  /**
   * Discards all cached tool lists, forcing a fresh fetch from each MCP server
   * on the next tool lookup.
   */
  def clearCache(): Unit = {
    logger.info("Clearing all tool caches")
    toolCache.clear()
  }

  /**
   * Eagerly re-fetches tool lists from all configured MCP servers and updates
   * the cache.  Useful after deploying changes to MCP server tool definitions.
   * Errors for individual servers are logged but do not prevent other servers
   * from being refreshed.
   */
  def refreshCache(): Unit = {
    logger.info(s"Refreshing cache for all ${mcpServers.size} MCP servers")
    val now = System.currentTimeMillis()
    mcpServers.foreach(server => refreshToolsFromServer(server, now))
    logger.info("Cache refresh completed for all servers")
  }

  /**
   * Closes all active MCP transport connections and removes them from the
   * internal client map.  After this call the registry still holds its local
   * tools; MCP tools will be re-fetched (creating new connections) on the
   * next lookup.
   */
  def closeMCPClients(): Unit = {
    logger.info(s"Closing ${mcpClients.size} MCP clients")
    mcpClients.values.asScala.foreach(_.close())
    mcpClients.clear()
    logger.info("All MCP clients closed")
  }

  private[mcp] def createAndInitializeClient(server: MCPServerConfig): ValidatedNel[String, MCPServerConfig] = {
    val status: Either[String, MCPServerConfig] = for {
      mcpClient <- Try(getOrCreateClient(server)).toEither.leftMap(_.getMessage)
      _         <- mcpClient.initialize().left.map(_.message)
    } yield server
    status.tap { x =>
      x.fold(
        err => logger.error(s"Failed to initialize MCP server '{}' : Error is {}", server.name, err),
        _ => logger.info(s"MCP server initialized: {}", server.name)
      )
    }
    status.toValidatedNel
  }

  /**
   * Attempts to initialise a connection to each configured MCP server and
   * reports whether the handshake succeeded.
   *
   * Each server is tested by creating (or reusing) its client and calling
   * `initialize()`. The returned map contains one entry per server, keyed by
   * `MCPServerConfig.name`, with `true` meaning the server responded
   * successfully and `false` meaning initialisation failed (connection refused,
   * timeout, protocol mismatch, etc.).
   *
   * @return map from server name to health status; keys match `mcpServers.map(_.name)`
   */
  def healthCheck(): Map[String, Boolean] = {
    logger.info("Performing health check on all MCP servers")
    val results = mcpServers.map(server => server.name -> createAndInitializeClient(server).isValid).toMap

    val healthyCount = results.values.count(identity)
    logger.info(s"Health check completed: $healthyCount/${mcpServers.size} servers healthy")
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

  // Initialize tools during construction unless explicitly disabled (useful for tests)
  if (initializeOnStartup) {
    initializeMCPTools()
  }

  override def close(): Unit =
    closeMCPClients()
}

object MCPToolRegistry {

  /**
   * A stdio config for the Playwright MCP server, fetched by `npx` at run time and unpinned. It starts with the
   * allowlisted environment only (#3070), like every stdio server.
   */
  @deprecated(
    "a registry's servers are the application's choice: build an MCPServerConfig.stdio with a pinned package (see the PlaywrightExample sample)",
    since = "1.1.0"
  )
  val mcpServerConfig: MCPServerConfig = MCPServerConfig.stdio(
    name = "playwright-mcp-server",
    command = Seq("npx", "@playwright/mcp@latest"),
    timeout = 60.seconds
  )

  /** A registry of the unpinned Playwright MCP server alone; see [[mcpServerConfig]]. */
  @deprecated(
    "use new MCPToolRegistry(Seq(MCPServerConfig.stdio(...))) with a pinned package (see the PlaywrightExample sample)",
    since = "1.1.0"
  )
  def apply(): MCPToolRegistry =
    new MCPToolRegistry(
      mcpServers = Seq(mcpServerConfig),
      localTools = Seq.empty, // No local tools, using only Playwright MCP tools
      cacheTTL = 10.minutes,
      initializeOnStartup = true
    )
}

// Helper case class for cleaner caching
// `tools` are what the registry hands out; `raw` the client's own, which call through `client`.
private case class CachedTools(
  tools: Seq[ToolFunction[_]],
  raw: Seq[ToolFunction[_]],
  client: MCPClient,
  timestamp: Long
) {
  def isExpired(now: Long, ttl: FiniteDuration): Boolean =
    (now - timestamp) >= ttl.toMillis
}
