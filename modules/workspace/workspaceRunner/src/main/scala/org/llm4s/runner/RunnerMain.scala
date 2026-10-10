// scalafix:off DisableSyntax.NoSystemGetenv
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
 * - Supports command cancellation via CancelCommandMessage
 * - Tracks processes per-client for proper isolation
 */
object RunnerMain extends cask.MainRoutes {

  private val logger                        = LoggerFactory.getLogger(getClass)
  private val executor                      = Executors.newCachedThreadPool()
  implicit private val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  // Get workspace path from environment variable or use default
  private val workspacePath = Option(System.getenv("WORKSPACE_PATH")).getOrElse("/workspace")

  // Detect host OS once and pass into the workspace interface (edge of configuration)
  private val isWindows: Boolean = System.getProperty("os.name").contains("Windows")

  // Resolve sandbox config:
  // - If WORKSPACE_SANDBOX_PROFILE is not set or empty -> default to permissive (backwards compatible)
  // - If set to a known profile name -> use that profile (validated)
  // - If set to an unknown name -> log error and fail fast (do NOT silently weaken sandbox)
  // - WORKSPACE_EXTRA_COMMANDS, when set, adds programs to that profile's allowlist; a name that is not a bare
  //   program name, or is a shell or launcher, fails fast too
  private val sandboxConfig: Option[WorkspaceSandboxConfig] = {
    val rawExtra = Option(System.getenv(WorkspaceSandboxConfig.ExtraCommandsEnvVar)).map(_.trim).filter(_.nonEmpty)
    rawExtra match {
      case None => profileConfig
      case Some(names) =>
        profileConfig.getOrElse(WorkspaceSandboxConfig.Permissive).withExtraCommands(names) match {
          case Right(cfg) =>
            logger.info(s"${WorkspaceSandboxConfig.ExtraCommandsEnvVar} adds to the allowlist: $names")
            Some(cfg)
          case Left(msg) =>
            logger.error(s"Invalid ${WorkspaceSandboxConfig.ExtraCommandsEnvVar} value: $msg")
            throw new IllegalArgumentException(msg)
        }
    }
  }

  private def profileConfig: Option[WorkspaceSandboxConfig] = {
    val rawProfile = Option(System.getenv("WORKSPACE_SANDBOX_PROFILE")).map(_.trim)

    rawProfile match {
      case None | Some("") =>
        None // let WorkspaceAgentInterfaceImpl apply default Permissive

      case Some(value) =>
        WorkspaceSandboxConfig.fromProfileName(value) match {
          case Right(cfg) =>
            WorkspaceSandboxConfig.validate(cfg) match {
              case Right(_) => Some(cfg)
              case Left(err) =>
                logger.error(s"Invalid WORKSPACE_SANDBOX_PROFILE config: $err; using permissive")
                None
            }

          case Left(msg) =>
            logger.error(s"Invalid WORKSPACE_SANDBOX_PROFILE value: $msg")
            throw new IllegalArgumentException(msg)
        }
    }
  }

  // Initialize workspace interface
  private val workspaceInterface = new WorkspaceAgentInterfaceImpl(workspacePath, isWindows, sandboxConfig)

  // Track active connections and their last heartbeat
  private val connections = new ConcurrentHashMap[cask.WsChannelActor, AtomicLong]()
  // Per-client process tracking (keyed by WebSocket channel to isolate clients)
  private val clientProcesses =
    new ConcurrentHashMap[cask.WsChannelActor, ConcurrentHashMap[String, RunningCommand]]()
  private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  // Constants
  private val HeartbeatTimeoutMs            = 30000L // 30 seconds timeout
  private val HeartbeatCheckIntervalSeconds = 10L
  // Max captured output size per stream for final ExecuteCommandResponse payload
  private val MaxOutputSize = 1024L * 1024L // 1MB per stream

  private val commandExecutor = new WebSocketCommandExecutor(workspaceInterface, MaxOutputSize)(ec)

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
          // Kill this client's processes only (per-client isolation)
          Option(clientProcesses.remove(channel)).foreach { processes =>
            processes.forEach { (_, running) =>
              running.cancelled.set(true)
              val terminateAttempt = Try {
                val destroyedProcess = running.process.destroyForcibly()
                if (!destroyedProcess.waitFor(5, TimeUnit.SECONDS)) {
                  logger.warn("Process did not terminate within timeout after disconnect")
                }
              }
              terminateAttempt.failed.foreach { ex =>
                logger.error(s"Error destroying process on disconnect: ${ex.getMessage}", ex)
              }
            }
          }
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

      case CancelCommandMessage(commandId) =>
        // Only kill THIS client's process. Cancellation is acknowledged as command completion (exit 143).
        commandExecutor.cancel(
          commandId,
          Option(clientProcesses.get(channel)),
          message => sendMessage(channel, message)
        )

      case HeartbeatMessage(timestamp) =>
        logger.debug(s"Received heartbeat at timestamp $timestamp")
        sendMessage(channel, HeartbeatResponseMessage(System.currentTimeMillis()))

      case _ =>
        logger.warn(s"Unexpected message type received: ${message.getClass.getSimpleName}")
        sendError(channel, s"Unexpected message type: ${message.getClass.getSimpleName}", "INVALID_MESSAGE_TYPE")
    }

  private def handleCommand(channel: cask.WsChannelActor, command: WorkspaceAgentCommand): Unit =
    Future {
      logger.debug(s"Processing command: ${command.getClass.getSimpleName} with ID: ${command.commandId}")
      command match {
        case cmd: ExecuteCommandCommand =>
          handleExecuteCommand(channel, cmd)
        case cmd: ExploreFilesCommand =>
          val result = scala.util
            .Try(
              workspaceInterface
                .exploreFiles(cmd.path, cmd.recursive, cmd.excludePatterns, cmd.maxDepth, cmd.returnMetadata)
                .copy(commandId = cmd.commandId)
            )
            .toEither
            .left
            .map(toErrorResponse(cmd.commandId))
          result.fold(
            err => sendMessage(channel, ResponseMessage(err)),
            ok => sendMessage(channel, ResponseMessage(ok))
          )
        case cmd: ReadFileCommand =>
          val result = scala.util
            .Try(
              workspaceInterface
                .readFile(cmd.path, cmd.startLine, cmd.endLine)
                .copy(commandId = cmd.commandId)
            )
            .toEither
            .left
            .map(toErrorResponse(cmd.commandId))
          result.fold(
            err => sendMessage(channel, ResponseMessage(err)),
            ok => sendMessage(channel, ResponseMessage(ok))
          )
        case cmd: WriteFileCommand =>
          val result = scala.util
            .Try(
              workspaceInterface
                .writeFile(cmd.path, cmd.content, cmd.mode, cmd.createDirectories)
                .copy(commandId = cmd.commandId)
            )
            .toEither
            .left
            .map(toErrorResponse(cmd.commandId))
          result.fold(
            err => sendMessage(channel, ResponseMessage(err)),
            ok => sendMessage(channel, ResponseMessage(ok))
          )
        case cmd: ModifyFileCommand =>
          val result = scala.util
            .Try(
              workspaceInterface
                .modifyFile(cmd.path, cmd.operations)
                .copy(commandId = cmd.commandId)
            )
            .toEither
            .left
            .map(toErrorResponse(cmd.commandId))
          result.fold(
            err => sendMessage(channel, ResponseMessage(err)),
            ok => sendMessage(channel, ResponseMessage(ok))
          )
        case cmd: SearchFilesCommand =>
          val result = scala.util
            .Try(
              workspaceInterface
                .searchFiles(cmd.paths, cmd.query, cmd.`type`, cmd.recursive, cmd.excludePatterns, cmd.contextLines)
                .copy(commandId = cmd.commandId)
            )
            .toEither
            .left
            .map(toErrorResponse(cmd.commandId))
          result.fold(
            err => sendMessage(channel, ResponseMessage(err)),
            ok => sendMessage(channel, ResponseMessage(ok))
          )
        case cmd: GetWorkspaceInfoCommand =>
          val result = Try(workspaceInterface.getWorkspaceInfo().copy(commandId = cmd.commandId)).toEither.left.map(
            toErrorResponse(cmd.commandId)
          )
          result.fold(
            err => sendMessage(channel, ResponseMessage(err)),
            ok => sendMessage(channel, ResponseMessage(ok))
          )
      }
    }(ec)

  private def toErrorResponse(commandId: String)(e: Throwable): WorkspaceAgentErrorResponse =
    e match {
      case ex: WorkspaceAgentException => WorkspaceAgentErrorResponse(commandId, ex.error, ex.code, ex.details)
      case ex: Exception =>
        WorkspaceAgentErrorResponse(
          commandId,
          Option(ex.getMessage).getOrElse("Execution failed"),
          "EXECUTION_FAILED",
          Some(ex.getStackTrace.mkString("\n"))
        )
    }

  /**
   * Handle ExecuteCommand with true streaming - sends StreamingOutputMessage chunks for stdout/stderr and a final
   * ExecuteCommandResponse. The command goes through the same checks and argv execution as the direct
   * `executeCommand` (`WorkspaceAgentInterfaceImpl.prepareCommand`), never through a shell (#1756).
   */
  private def handleExecuteCommand(channel: cask.WsChannelActor, cmd: ExecuteCommandCommand): Unit = {
    // Ensure client has a process map (per-client isolation)
    val processes = clientProcesses.computeIfAbsent(channel, _ => new ConcurrentHashMap[String, RunningCommand]())
    Future(commandExecutor.execute(cmd, processes, message => sendMessage(channel, message)))(ec)
  }

  private def sendMessage(channel: cask.WsChannelActor, message: WebSocketMessage): Unit = {
    val attempt = Try(write(message)).toEither
    for {
      json <- attempt.left.map(ex => logger.error(s"Failed to serialize WebSocket message: ${ex.getMessage}", ex))
    } yield channel.send(cask.Ws.Text(json))
    logger.debug(s"Sent WebSocket message: ${message.getClass.getSimpleName}")
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
            Try(channel.send(cask.Ws.Close(1000, "Heartbeat timeout"))).failed.foreach { ex =>
              logger.error(s"Error closing timed out connection: ${ex.getMessage}", ex)
            }
            // Also clean up processes for timed out connection
            Option(clientProcesses.remove(channel)).foreach { processes =>
              processes.forEach { (_, running) =>
                running.cancelled.set(true)
                val terminateAttempt = Try {
                  val destroyedProcess = running.process.destroyForcibly()
                  if (!destroyedProcess.waitFor(5, TimeUnit.SECONDS)) {
                    logger.warn("Process did not terminate within timeout after heartbeat disconnect")
                  }
                }
                terminateAttempt.failed.foreach { ex =>
                  logger.error(s"Error destroying process on heartbeat timeout: ${ex.getMessage}", ex)
                }
              }
            }
            connections.remove(channel)
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
    logger.info(s"Sandbox profile: ${sandboxConfig.map(_ => "configured").getOrElse("permissive (default)")}")
    logger.info(s"Heartbeat timeout: ${HeartbeatTimeoutMs}ms")
    logger.info(s"Max output size per stream: ${MaxOutputSize / 1024}KB")
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

    logger.info(s"WebSocket Runner service starting on $host:$port")
    super.main(args)
  }

  /**
   * Gracefully shuts down the service.
   */
  def shutdown(): Unit = {
    logger.info("Shutting down WebSocket Runner service")

    // Kill all remaining processes
    clientProcesses.forEach { (_, processes) =>
      processes.forEach { (_, running) =>
        running.cancelled.set(true)
        val terminateAttempt = Try {
          val destroyedProcess = running.process.destroyForcibly()
          if (!destroyedProcess.waitFor(5, TimeUnit.SECONDS)) {
            logger.warn("Process did not terminate within timeout during shutdown")
          }
        }
        terminateAttempt.failed.foreach { ex =>
          logger.error(s"Error destroying process on shutdown: ${ex.getMessage}", ex)
        }
      }
    }
    clientProcesses.clear()

    Try {
      heartbeatExecutor.shutdown()
      if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) heartbeatExecutor.shutdownNow()
    }.recover { case _: InterruptedException => heartbeatExecutor.shutdownNow() }

    Try {
      executor.shutdown()
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }.recover { case _: InterruptedException => executor.shutdownNow() }
  }
}
