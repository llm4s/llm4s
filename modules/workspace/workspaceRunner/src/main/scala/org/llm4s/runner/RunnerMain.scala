package org.llm4s.runner

import org.llm4s.shared._
import org.slf4j.LoggerFactory
import upickle.default._

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import java.util.concurrent.{ ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit }
import scala.concurrent.{ ExecutionContext, Future, Promise }
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
  // scalafix:off DisableSyntax.NoSystemGetenv
  private val workspacePath = Option(System.getenv("WORKSPACE_PATH")).getOrElse("/workspace")
  // scalafix:on DisableSyntax.NoSystemGetenv

  // Detect host OS once and pass into the workspace interface (edge of configuration)
  private val isWindows: Boolean = System.getProperty("os.name").contains("Windows")

  // Resolve sandbox config:
  // - If WORKSPACE_SANDBOX_PROFILE is not set or empty -> default to permissive (backwards compatible)
  // - If set to a known profile name -> use that profile (validated)
  // - If set to an unknown name -> log error and fail fast (do NOT silently weaken sandbox)
  // scalafix:off DisableSyntax.NoSystemGetenv
  private val sandboxConfig: Option[WorkspaceSandboxConfig] = {
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
  // scalafix:on DisableSyntax.NoSystemGetenv

  // Initialize workspace interface
  private val workspaceInterface     = new WorkspaceAgentInterfaceImpl(workspacePath, isWindows, sandboxConfig)
  private val effectiveSandboxConfig = sandboxConfig.getOrElse(WorkspaceSandboxConfig.Permissive)
  private val workspaceRootPath      = Paths.get(workspacePath).toAbsolutePath.normalize()

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

  private case class RunningCommand(
    process: Process,
    cancelled: AtomicBoolean,
    startTimeMs: Long,
    completionSent: AtomicBoolean
  )

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
        // Handle cancellation - only kill THIS client's process.
        // Cancellation is acknowledged as command completion (exit 143).
        val runningOpt = Option(clientProcesses.get(channel))
          .flatMap(processes => Option(processes.get(commandId)))
        runningOpt match {
          case Some(running) =>
            running.cancelled.set(true)
            val process = running.process
            val terminationAttempt = Try {
              val destroyedProcess = process.destroyForcibly()
              // Wait briefly for the process to actually terminate
              if (!destroyedProcess.waitFor(5, TimeUnit.SECONDS)) {
                logger.warn(s"Process for command $commandId did not terminate within timeout after cancellation")
              }
            }
            terminationAttempt.failed.foreach { ex =>
              logger.error(s"Error destroying process for command $commandId: ${ex.getMessage}", ex)
            }
            logger.info(s"Command $commandId cancelled by client")
            // Acknowledge cancellation as completion, not as an error.
            if (running.completionSent.compareAndSet(false, true)) {
              val durationMs = System.currentTimeMillis() - running.startTimeMs
              sendMessage(channel, CommandCompletedMessage(commandId, 143, durationMs))
            }

          case None =>
            logger.warn(s"Cancellation requested for unknown or non-running command id $commandId")
            sendError(channel, s"No running command found for id $commandId", "UNKNOWN_COMMAND_ID", Some(commandId))
        }
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

  private def sendCommandFailure(
    channel: cask.WsChannelActor,
    commandId: String,
    error: String,
    code: String,
    details: Option[String],
    exitCode: Int,
    durationMs: Long
  ): Unit = {
    sendMessage(channel, ResponseMessage(WorkspaceAgentErrorResponse(commandId, error, code, details)))
    sendMessage(channel, CommandCompletedMessage(commandId, exitCode, durationMs))
  }

  /**
   * Handle ExecuteCommand with true streaming - sends StreamingOutputMessage chunks
   * for stdout/stderr and also sends final ExecuteCommandResponse for backward compatibility.
   */
  private def handleExecuteCommand(channel: cask.WsChannelActor, cmd: ExecuteCommandCommand): Unit = {
    // Ensure client has a process map (per-client isolation)
    val processes = clientProcesses.computeIfAbsent(channel, _ => new ConcurrentHashMap[String, RunningCommand]())

    Future {
      val startTime = System.currentTimeMillis()

      if (!effectiveSandboxConfig.shellAllowed) {
        sendCommandFailure(
          channel,
          cmd.commandId,
          "Shell execution is disabled by sandbox config (shellAllowed=false)",
          "SHELL_DISABLED",
          None,
          exitCode = 1,
          durationMs = System.currentTimeMillis() - startTime
        )
      } else {
        sendMessage(channel, CommandStartedMessage(cmd.commandId, cmd.command))

        resolveWorkingDirectory(cmd.workingDirectory).fold(
          err =>
            sendCommandFailure(
              channel,
              cmd.commandId,
              err.error,
              err.code,
              err.details,
              exitCode = 1,
              durationMs = System.currentTimeMillis() - startTime
            ),
          workDir => {
            val builder =
              if (isWindows)
                new ProcessBuilder("cmd.exe", "/c", cmd.command)
              else
                new ProcessBuilder("sh", "-c", cmd.command)

            builder.directory(workDir)
            cmd.environment.foreach { env =>
              val pbEnv = builder.environment()
              env.foreach { case (k, v) => pbEnv.put(k, v) }
            }

            val processEither = Try(builder.start()).toEither
            processEither.fold(
              ex =>
                sendCommandFailure(
                  channel,
                  cmd.commandId,
                  Option(ex.getMessage).getOrElse("Failed to start process"),
                  "EXECUTION_FAILED",
                  Some(ex.getStackTrace.mkString("\n")),
                  exitCode = 1,
                  durationMs = System.currentTimeMillis() - startTime
                ),
              process => {
                val running =
                  RunningCommand(process, new AtomicBoolean(false), startTime, new AtomicBoolean(false))
                processes.put(cmd.commandId, running)

                val stdoutDone        = Promise[Unit]()
                val stderrDone        = Promise[Unit]()
                val exitDone          = Promise[Unit]()
                val exitCodePromise   = Promise[Int]()
                val stdoutTruncated   = new AtomicBoolean(false)
                val stderrTruncated   = new AtomicBoolean(false)
                val commandTimedOut   = new AtomicBoolean(false)
                val stdoutAccumulator = new StringBuilder()
                val stderrAccumulator = new StringBuilder()

                def streamOutput(
                  outputType: String,
                  stream: java.io.InputStream,
                  accumulator: StringBuilder,
                  truncated: AtomicBoolean,
                  done: Promise[Unit]
                ): Unit =
                  Future {
                    val buffer    = new Array[Byte](8192)
                    var bytesRead = 0
                    var captured  = 0L

                    try
                      while ({
                        bytesRead = stream.read(buffer)
                        bytesRead != -1
                      }) {
                        val chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                        sendMessage(channel, StreamingOutputMessage(cmd.commandId, outputType, chunk))

                        if (captured < MaxOutputSize) {
                          val remaining = (MaxOutputSize - captured).toInt
                          val toCopy    = math.min(remaining, bytesRead)
                          if (toCopy > 0) {
                            val toAppend = if (toCopy == bytesRead) chunk else chunk.substring(0, toCopy)
                            accumulator.append(toAppend)
                            captured += toCopy
                          }
                          if (toCopy < bytesRead) truncated.set(true)
                        } else truncated.set(true)
                      }
                    catch {
                      case ex: Exception =>
                        logger.error(s"Error reading $outputType for command ${cmd.commandId}: ${ex.getMessage}", ex)
                    } finally {
                      sendMessage(channel, StreamingOutputMessage(cmd.commandId, outputType, "", isComplete = true))
                      done.trySuccess(())
                    }
                  }(ec)

                streamOutput("stdout", process.getInputStream, stdoutAccumulator, stdoutTruncated, stdoutDone)
                streamOutput("stderr", process.getErrorStream, stderrAccumulator, stderrTruncated, stderrDone)

                Future {
                  val exitCode =
                    try {
                      val timeoutDeadlineMs = cmd.timeout.map(timeoutSec => startTime + (timeoutSec.toLong * 1000L))
                      timeoutDeadlineMs match {
                        case Some(deadlineMs) =>
                          var finished = false
                          var timedOut = false
                          while (!finished && !running.cancelled.get())
                            if (System.currentTimeMillis() >= deadlineMs) {
                              timedOut = true
                              finished = true
                            } else {
                              finished = process.waitFor(500, TimeUnit.MILLISECONDS)
                            }
                          if (running.cancelled.get()) {
                            process.destroyForcibly()
                            143
                          } else if (timedOut) {
                            commandTimedOut.set(true)
                            process.destroyForcibly()
                            -1
                          } else {
                            process.exitValue()
                          }
                        case None =>
                          process.waitFor()
                      }
                    } catch {
                      case _: InterruptedException =>
                        Thread.currentThread().interrupt()
                        process.destroyForcibly()
                        -1
                      case ex: Exception =>
                        logger.error(s"Error waiting for process ${cmd.commandId}: ${ex.getMessage}", ex)
                        process.destroyForcibly()
                        -1
                    } finally {
                      processes.remove(cmd.commandId)
                      exitDone.trySuccess(())
                    }
                  exitCodePromise.trySuccess(exitCode)
                }(ec)

                stdoutDone.future
                  .flatMap(_ => stderrDone.future)(ec)
                  .flatMap(_ => exitDone.future)(ec)
                  .flatMap(_ => exitCodePromise.future)(ec)
                  .onComplete {
                    case Success(rawExitCode) =>
                      val durationMs = System.currentTimeMillis() - startTime
                      val effectiveExitCode =
                        if (running.cancelled.get()) 143
                        else if (commandTimedOut.get()) -1
                        else rawExitCode
                      val isOutputTruncated = stdoutTruncated.get() || stderrTruncated.get()

                      sendMessage(
                        channel,
                        ResponseMessage(
                          ExecuteCommandResponse(
                            commandId = cmd.commandId,
                            stdout = stdoutAccumulator.result(),
                            stderr = stderrAccumulator.result(),
                            exitCode = effectiveExitCode,
                            isOutputTruncated = isOutputTruncated,
                            durationMs = durationMs
                          )
                        )
                      )
                      if (running.completionSent.compareAndSet(false, true)) {
                        sendMessage(channel, CommandCompletedMessage(cmd.commandId, effectiveExitCode, durationMs))
                      }
                    case Failure(ex) =>
                      sendCommandFailure(
                        channel,
                        cmd.commandId,
                        Option(ex.getMessage).getOrElse("Command execution failed"),
                        "EXECUTION_FAILED",
                        Some(ex.getStackTrace.mkString("\n")),
                        exitCode = 1,
                        durationMs = System.currentTimeMillis() - startTime
                      )
                  }(ec)
              }
            )
          }
        )
      }
    }(ec)
  }

  private def resolveWorkingDirectory(workingDirectory: Option[String]): Either[WorkspaceAgentException, java.io.File] =
    Try {
      workingDirectory match {
        case Some(dir) => workspaceRootPath.resolve(dir).normalize()
        case None      => workspaceRootPath
      }
    }.toEither match {
      case Left(e) =>
        Left(
          new WorkspaceAgentException(
            Option(e.getMessage).getOrElse("Invalid working directory"),
            "INVALID_DIRECTORY",
            None
          )
        )
      case Right(candidatePath) =>
        if (!candidatePath.startsWith(workspaceRootPath)) {
          Left(
            new WorkspaceAgentException(
              s"Working directory '$workingDirectory' attempts to escape the workspace",
              "INVALID_DIRECTORY",
              None
            )
          )
        } else {
          val candidate = candidatePath.toFile
          if (!candidate.exists() || !candidate.isDirectory) {
            Left(
              new WorkspaceAgentException(
                "Invalid working directory",
                "INVALID_DIRECTORY",
                Some(s"Working directory does not exist: ${candidate.getPath}")
              )
            )
          } else {
            Right(candidate)
          }
        }
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
