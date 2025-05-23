package org.llm4s.runner

import cask.model.Response
import org.llm4s.shared.*
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService, TimeUnit}

/**
 * Main entry point for the Workspace Runner service.
 *
 * This service provides a REST API for interacting with a workspace filesystem and executing commands.
 * It's designed to run inside a container with a mounted workspace directory, providing a secure
 * execution environment for LLM-driven operations.
 *
 * Features:
 * - REST API for workspace operations (file exploration, reading, writing, etc.)
 * - Command execution in the workspace
 * - Heartbeat mechanism to ensure the service is responsive
 * - Automatic shutdown if no heartbeat is received within the timeout period
 *
 * The service binds to 0.0.0.0 to be accessible from outside the container.
 */
object RunnerMain extends cask.MainRoutes {

  private val logger                                     = LoggerFactory.getLogger(getClass)
  private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
  private val lastHeartbeatTime                          = new AtomicLong(System.currentTimeMillis())
  private val heartbeatTimeoutMs                         = 10000L // 10 seconds in milliseconds

  //Use the same underlying executor for both virtual and non-virtual threads
  private val executor = Executors.newFixedThreadPool(4)

  override protected def handlerExecutor(): Option[ExecutorService] = {
    super.handlerExecutor().orElse(Some(executor))
  }


  // Get workspace path from environment variable or use default
  private val workspacePath = sys.env.getOrElse("WORKSPACE_PATH", "/workspace")

  // Initialize workspace interface
  val workspaceInterface = new WorkspaceAgentInterfaceImpl(workspacePath)

  // default is localhost - macs bind localhost to ipv6 by default
  // so it doesn't work correctly.  This is a workaround
  // 0.0.0.0 binds to all interfaces - find for us as we are going
  // to run this in a docker container.
  // On mac you can bind to 'localhost' or 127.0.0.1 which changes
  // the behaviour but not sure what impact that has on other platforms
  override def host: String = "0.0.0.0"

  /**
   * Root endpoint that provides basic information about the service.
   *
   * @return A simple message indicating the service is running
   */
  @cask.get("/")
  def root(): String =
    "LLM4S Runner service - please use the rest endpoint"

  /**
   * Main endpoint for handling workspace agent commands.
   *
   * This endpoint accepts WorkspaceAgentCommand objects as JSON and routes them to the
   * appropriate handler in the WorkspaceAgentInterface implementation. It handles error
   * cases and returns properly formatted responses.
   *
   * @param request The HTTP request containing a serialized WorkspaceAgentCommand
   * @return A Response containing the serialized WorkspaceAgentResponse
   */
  @cask.post("/agent")
  def agentCommand(request: cask.Request): Response[String] = {
    // Refresh heartbeat on any command execution
    updateHeartbeat()
    try {
      val requestBody = request.text()
      val command     = ProtocolCodec.decodeAgentCommand(requestBody)

      val response =
        try
          command match {
            case cmd: ExploreFilesCommand =>
              workspaceInterface
                .exploreFiles(
                  cmd.path,
                  cmd.recursive,
                  cmd.excludePatterns,
                  cmd.maxDepth,
                  cmd.returnMetadata
                )
                .copy(commandId = cmd.commandId)

            case cmd: ReadFileCommand =>
              workspaceInterface
                .readFile(
                  cmd.path,
                  cmd.startLine,
                  cmd.endLine
                )
                .copy(commandId = cmd.commandId)

            case cmd: WriteFileCommand =>
              workspaceInterface
                .writeFile(
                  cmd.path,
                  cmd.content,
                  cmd.mode,
                  cmd.createDirectories
                )
                .copy(commandId = cmd.commandId)

            case cmd: ModifyFileCommand =>
              workspaceInterface
                .modifyFile(
                  cmd.path,
                  cmd.operations
                )
                .copy(commandId = cmd.commandId)

            case cmd: SearchFilesCommand =>
              workspaceInterface
                .searchFiles(
                  cmd.paths,
                  cmd.query,
                  cmd.`type`,
                  cmd.recursive,
                  cmd.excludePatterns,
                  cmd.contextLines
                )
                .copy(commandId = cmd.commandId)

            case cmd: ExecuteCommandCommand =>
              workspaceInterface
                .executeCommand(
                  cmd.command,
                  cmd.workingDirectory,
                  cmd.timeout,
                  cmd.environment
                )
                .copy(commandId = cmd.commandId)

            case cmd: GetWorkspaceInfoCommand =>
              workspaceInterface.getWorkspaceInfo().copy(commandId = cmd.commandId)
          }
        catch {
          case e: WorkspaceAgentException =>
            WorkspaceAgentErrorResponse(
              commandId = command.commandId,
              error = e.error,
              code = e.code,
              details = e.details
            )
          case e: Exception =>
            WorkspaceAgentErrorResponse(
              commandId = command.commandId,
              error = e.getMessage,
              code = "EXECUTION_FAILED",
              details = Some(e.getStackTrace.mkString("\n"))
            )
        }

      val responseJson = ProtocolCodec.encodeAgentResponse(response)
      cask.Response(responseJson, 200)
    } catch {
      case e: Exception =>
        val errorResponse = WorkspaceAgentErrorResponse(
          commandId = "unknown",
          error = s"Failed to process request: ${e.getMessage}",
          code = "INVALID_REQUEST",
          details = Some(e.getStackTrace.mkString("\n"))
        )
        cask.Response(ProtocolCodec.encodeAgentResponse(errorResponse), 400)
    }
  }

  /**
   * Heartbeat endpoint to verify the service is alive.
   *
   * The ContainerisedWorkspace class periodically calls this endpoint to ensure
   * the service is still responsive. If no heartbeat is received within the timeout
   * period, the service will shut down.
   *
   * @return A simple "ok" response
   */
  @cask.get("/heartbeat")
  def heartbeat(): String = {
    logger.info("HEARTTBEAT")
    updateHeartbeat()
    "ok"
  }

  /**
   * Endpoint to retrieve information about the workspace.
   *
   * This provides details about the workspace configuration, including the root path
   * and any relevant settings or limitations.
   *
   * @return A Response containing the serialized workspace information
   */
  @cask.get("/workspace-info")
  def workspaceInfo(): Response[String] = {
    updateHeartbeat()
    try {
      val info = workspaceInterface.getWorkspaceInfo()
      cask.Response(ProtocolCodec.encodeAgentResponse(info), 200)
    } catch {
      case e: Exception =>
        val errorResponse = WorkspaceAgentErrorResponse(
          commandId = "info-request",
          error = e.getMessage,
          code = "EXECUTION_FAILED",
          details = None
        )
        cask.Response(ProtocolCodec.encodeAgentResponse(errorResponse), 500)
    }
  }

  /**
   * Updates the last heartbeat timestamp.
   *
   * This is called whenever a heartbeat is received or when any command is executed,
   * to prevent the service from shutting down during active use.
   */
  private def updateHeartbeat(): Unit = {
    lastHeartbeatTime.set(System.currentTimeMillis())
    logger.debug("Heartbeat received")
  }

  /**
   * Starts the watchdog timer that monitors for heartbeats.
   *
   * If no heartbeat is received within the configured timeout period,
   * the service will shut down. This prevents orphaned processes if
   * the parent application crashes or loses connection.
   */
  private def startWatchdog(): Unit =
    watchdogExecutor.scheduleAtFixedRate(
      () => {
        val currentTime            = System.currentTimeMillis()
        val timeSinceLastHeartbeat = currentTime - lastHeartbeatTime.get()

        if (timeSinceLastHeartbeat > heartbeatTimeoutMs) {
          logger.warn(s"No heartbeat received in ${timeSinceLastHeartbeat}ms, shutting down")
          watchdogExecutor.shutdown()
          System.exit(1)
        }
      },
      2,
      2,
      TimeUnit.SECONDS
    )

  initialize()

  /**
   * Main entry point for the application.
   *
   * Initializes the service, ensures the workspace directory exists,
   * starts the watchdog timer, and begins listening for requests.
   *
   * @param args Command line arguments (not used)
   */
  override def main(args: Array[String]): Unit = {
    super.main(args)

    // Ensure workspace directory exists
    val workspaceDir = Paths.get(workspacePath)
    if (!Files.exists(workspaceDir)) {
      logger.info(s"Creating workspace directory: $workspacePath")
      Files.createDirectories(workspaceDir)
    }

    startWatchdog()
    logger.info(s"LLM4S Runner service started on ${host}:${port}")
    logger.info(s"Using workspace path: $workspacePath")
    logger.info(s"Watchdog timer initialized with ${heartbeatTimeoutMs}ms timeout")
  }

  /**
   * Gracefully shuts down the service.
   *
   * Stops the watchdog timer and performs any necessary cleanup.
   */
  def shutdown(): Unit =
    watchdogExecutor.shutdown()
}
