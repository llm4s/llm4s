package org.llm4s.runner

import org.llm4s.shared._
import org.slf4j.LoggerFactory
import upickle.default._

import java.nio.file.{ Files, Paths }
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
 * WebSocket-based Workspace Runner service using Cask's native WebSocket support.
 *
 * This replaces the HTTP-based RunnerMain with a WebSocket implementation that:
 * - Handles commands asynchronously without blocking threads
 * - Provides real-time streaming of command output
 * - Supports heartbeat mechanism over the same connection
 * - Eliminates the threading issues of the HTTP version
 */
object RunnerMain extends cask.MainRoutes {

  private val logger                        = LoggerFactory.getLogger(getClass)
  private val executor                      = Executors.newCachedThreadPool()
  implicit private val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  // Get workspace path from environment variable or use default
  private val workspacePath = sys.env.getOrElse("WORKSPACE_PATH", "/workspace")

  // Initialize workspace interface
  private val workspaceInterface = new WorkspaceAgentInterfaceImpl(workspacePath)

  // Track active connections and their last heartbeat
  private val connections                                 = new ConcurrentHashMap[cask.WsChannelActor, AtomicLong]()
  private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  // Constants
  private val HeartbeatTimeoutMs            = 30000L // 30 seconds timeout
  private val HeartbeatCheckIntervalSeconds = 10L

  // Default host binding - 0.0.0.0 to be accessible from outside container
  override def host: String = "0.0.0.0"

  /**
   * Root endpoint that provides basic information about the service.
   */
  @cask.get("/")
  def root(): String = "LLM4S WebSocket Runner service - connect via WebSocket at /ws"

  /**
   * WebSocket endpoint for workspace agent communication.
   */
  @cask.websocket("/ws")
  def websocketHandler(): cask.WebsocketResult =
    cask.WsHandler { channel =>
      logger.info(s"WebSocket connection opened")
      connections.put(channel, new AtomicLong(System.currentTimeMillis()))

      cask.WsActor {
        case cask.Ws.Text(message) =>
          handleWebSocketMessage(channel, message)

        case cask.Ws.Close(code, reason) =>
          logger.info(s"WebSocket connection closed: code=$code, reason=$reason")
          connections.remove(channel)

        case cask.Ws.Error(ex) =>
          logger.error(s"WebSocket error: ${ex.getMessage}", ex)
          connections.remove(channel)
      }
    }

  private def handleWebSocketMessage(channel: cask.WsChannelActor, message: String): Unit = {
    logger.debug(s"Received WebSocket message: $message")

    // Update heartbeat timestamp for this connection
    Option(connections.get(channel)).foreach(_.set(System.currentTimeMillis()))

    Try(read[WebSocketMessage](message)) match {
      case Success(msg) => handleMessage(channel, msg)
      case Failure(ex) =>
        logger.error(s"Failed to parse WebSocket message: ${ex.getMessage}", ex)
        sendError(channel, "Invalid message format", "PARSE_ERROR")
    }
  }

  private def handleMessage(channel: cask.WsChannelActor, message: WebSocketMessage): Unit =
    message match {
      case CommandMessage(command) =>
        handleCommand(channel, command)

      case HeartbeatMessage(timestamp) =>
        logger.debug(s"Received heartbeat at timestamp $timestamp")
        sendMessage(channel, HeartbeatResponseMessage(System.currentTimeMillis()))

      case _ =>
        logger.warn(s"Unexpected message type received: ${message.getClass.getSimpleName}")
        sendError(channel, s"Unexpected message type: ${message.getClass.getSimpleName}", "INVALID_MESSAGE_TYPE")
    }

  private def handleCommand(channel: cask.WsChannelActor, command: WorkspaceAgentCommand): Unit =
    // Handle commands asynchronously to avoid blocking the WebSocket thread
    Future {
      try {
        logger.debug(s"Processing command: ${command.getClass.getSimpleName} with ID: ${command.commandId}")

        command match {
          case cmd: ExploreFilesCommand =>
            val response = workspaceInterface
              .exploreFiles(
                cmd.path,
                cmd.recursive,
                cmd.excludePatterns,
                cmd.maxDepth,
                cmd.returnMetadata
              )
              .copy(commandId = cmd.commandId)
            sendMessage(channel, ResponseMessage(response))

          case cmd: ReadFileCommand =>
            val response = workspaceInterface
              .readFile(
                cmd.path,
                cmd.startLine,
                cmd.endLine
              )
              .copy(commandId = cmd.commandId)
            sendMessage(channel, ResponseMessage(response))

          case cmd: WriteFileCommand =>
            val response = workspaceInterface
              .writeFile(
                cmd.path,
                cmd.content,
                cmd.mode,
                cmd.createDirectories
              )
              .copy(commandId = cmd.commandId)
            sendMessage(channel, ResponseMessage(response))

          case cmd: ModifyFileCommand =>
            val response = workspaceInterface
              .modifyFile(
                cmd.path,
                cmd.operations
              )
              .copy(commandId = cmd.commandId)
            sendMessage(channel, ResponseMessage(response))

          case cmd: SearchFilesCommand =>
            val response = workspaceInterface
              .searchFiles(
                cmd.paths,
                cmd.query,
                cmd.`type`,
                cmd.recursive,
                cmd.excludePatterns,
                cmd.contextLines
              )
              .copy(commandId = cmd.commandId)
            sendMessage(channel, ResponseMessage(response))

          case cmd: ExecuteCommandCommand =>
            // For execute command, we'll implement streaming in a separate method
            handleExecuteCommand(channel, cmd)

          case cmd: GetWorkspaceInfoCommand =>
            val response = workspaceInterface.getWorkspaceInfo().copy(commandId = cmd.commandId)
            sendMessage(channel, ResponseMessage(response))
        }

      } catch {
        case e: WorkspaceAgentException =>
          logger.error(s"Workspace agent error processing command ${command.commandId}: ${e.getMessage}", e)
          val errorResponse = WorkspaceAgentErrorResponse(
            commandId = command.commandId,
            error = e.error,
            code = e.code,
            details = e.details
          )
          sendMessage(channel, ResponseMessage(errorResponse))

        case e: Exception =>
          logger.error(s"Unexpected error processing command ${command.commandId}: ${e.getMessage}", e)
          val errorResponse = WorkspaceAgentErrorResponse(
            commandId = command.commandId,
            error = e.getMessage,
            code = "EXECUTION_FAILED",
            details = Some(e.getStackTrace.mkString("\n"))
          )
          sendMessage(channel, ResponseMessage(errorResponse))
      }
    }(using ec)

  private def handleExecuteCommand(channel: cask.WsChannelActor, cmd: ExecuteCommandCommand): Unit =
    Future {
      try {
        // Send command started message
        sendMessage(channel, CommandStartedMessage(cmd.commandId, cmd.command))

        // Execute command with streaming output
        val response = executeCommandWithStreaming(cmd)

        // Send final response
        sendMessage(channel, ResponseMessage(response))

        // Send command completed message
        sendMessage(channel, CommandCompletedMessage(cmd.commandId, response.exitCode, response.durationMs))

      } catch {
        case e: WorkspaceAgentException =>
          logger.error(s"Error executing command ${cmd.commandId}: ${e.getMessage}", e)
          val errorResponse = WorkspaceAgentErrorResponse(
            commandId = cmd.commandId,
            error = e.error,
            code = e.code,
            details = e.details
          )
          sendMessage(channel, ResponseMessage(errorResponse))

        case e: Exception =>
          logger.error(s"Unexpected error executing command ${cmd.commandId}: ${e.getMessage}", e)
          val errorResponse = WorkspaceAgentErrorResponse(
            commandId = cmd.commandId,
            error = e.getMessage,
            code = "EXECUTION_FAILED",
            details = Some(e.getStackTrace.mkString("\n"))
          )
          sendMessage(channel, ResponseMessage(errorResponse))
      }
    }(using ec)

  private def executeCommandWithStreaming(cmd: ExecuteCommandCommand): ExecuteCommandResponse =
    // For now, use the existing implementation but we can enhance this later to provide real streaming
    workspaceInterface
      .executeCommand(
        cmd.command,
        cmd.workingDirectory,
        cmd.timeout,
        cmd.environment
      )
      .copy(commandId = cmd.commandId)

  private def sendMessage(channel: cask.WsChannelActor, message: WebSocketMessage): Unit =
    try {
      val json = write(message)
      channel.send(cask.Ws.Text(json))
      logger.debug(s"Sent WebSocket message: ${message.getClass.getSimpleName}")
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to send WebSocket message: ${ex.getMessage}", ex)
    }

  private def sendError(
    channel: cask.WsChannelActor,
    error: String,
    code: String,
    commandId: Option[String] = None
  ): Unit =
    sendMessage(channel, ErrorMessage(error, code, commandId))

  private def startHeartbeatMonitor(): Unit =
    heartbeatExecutor.scheduleAtFixedRate(
      () => {
        val currentTime = System.currentTimeMillis()
        val iterator    = connections.entrySet().iterator()

        while (iterator.hasNext) {
          val entry         = iterator.next()
          val channel       = entry.getKey
          val lastHeartbeat = entry.getValue.get()

          if (currentTime - lastHeartbeat > HeartbeatTimeoutMs) {
            logger.warn(s"WebSocket connection timed out - no heartbeat for ${currentTime - lastHeartbeat}ms")
            try {
              channel.send(cask.Ws.Close(1000, "Heartbeat timeout"))
              connections.remove(channel)
            } catch {
              case ex: Exception =>
                logger.error(s"Error closing timed out connection: ${ex.getMessage}", ex)
            }
          }
        }
      },
      HeartbeatCheckIntervalSeconds,
      HeartbeatCheckIntervalSeconds,
      TimeUnit.SECONDS
    )

  // Initialize the service
  private def initializeService(): Unit = {
    // Ensure workspace directory exists
    val workspaceDir = Paths.get(workspacePath)
    if (!Files.exists(workspaceDir)) {
      logger.info(s"Creating workspace directory: $workspacePath")
      Files.createDirectories(workspaceDir)
    }

    startHeartbeatMonitor()
    logger.info(s"Using workspace path: $workspacePath")
    logger.info(s"Heartbeat timeout: ${HeartbeatTimeoutMs}ms")
  }

  // Call initialize when the object is created
  initializeService()

  // Make this object available to be called from the old RunnerMain for backward compatibility
  initialize()

  /**
   * Main entry point for the application.
   */
  override def main(args: Array[String]): Unit = {
    // Add shutdown hook
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("Shutdown hook triggered")
      shutdown()
    }))

    logger.info(s"WebSocket Runner service starting on ${host}:${port}")
    super.main(args)
  }

  /**
   * Gracefully shuts down the service.
   */
  def shutdown(): Unit = {
    logger.info("Shutting down WebSocket Runner service")

    try {
      heartbeatExecutor.shutdown()
      if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        heartbeatExecutor.shutdownNow()
      }
    } catch {
      case _: InterruptedException => heartbeatExecutor.shutdownNow()
    }

    try {
      executor.shutdown()
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow()
      }
    } catch {
      case _: InterruptedException => executor.shutdownNow()
    }
  }
}
