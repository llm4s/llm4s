package org.llm4s.workspace

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.llm4s.shared._
import org.slf4j.LoggerFactory
import upickle.default._

import java.io.{ BufferedReader, File, InputStreamReader }
import java.net.URI
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ CompletableFuture, ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

/**
 * WebSocket-based implementation of ContainerisedWorkspace that communicates with
 * the workspace runner via WebSocket instead of HTTP.
 *
 * This solves the threading issues of the HTTP version by:
 * - Using asynchronous WebSocket communication
 * - Implementing heartbeats over the same connection
 * - Supporting real-time streaming of command output
 * - Eliminating thread pool blocking during long commands
 */
class ContainerisedWorkspace(val workspaceDir: String) extends WorkspaceAgentInterface {
  private val logger        = LoggerFactory.getLogger(getClass)
  private val containerName = s"workspace-runner-${java.util.UUID.randomUUID().toString}"
  private val port          = 8080
  private val wsUrl         = s"ws://localhost:$port/ws"

  // Constants
  private val HeartbeatIntervalSeconds = 5
  private val MaxStartupAttempts       = 10
  private val ConnectionTimeoutMs      = 10000L

  // State tracking
  private val containerRunning                            = new AtomicBoolean(false)
  private val wsConnected                                 = new AtomicBoolean(false)
  private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  // WebSocket client and response handling
  private val wsClient          = new AtomicReference[WebSocketClient]()
  private val pendingResponses  = new ConcurrentHashMap[String, CompletableFuture[WorkspaceAgentResponse]]()
  private val streamingHandlers = new ConcurrentHashMap[String, StreamingOutputMessage => Unit]()

  /**
   * Custom WebSocket client that handles workspace agent protocol
   */
  private class WorkspaceWebSocketClient(serverUri: URI) extends WebSocketClient(serverUri) {

    override def onOpen(handshake: ServerHandshake): Unit = {
      logger.info("WebSocket connection opened to workspace runner")
      wsConnected.set(true)
    }

    override def onMessage(message: String): Unit = {
      logger.debug(s"Received WebSocket message: $message")
      handleWebSocketMessage(message)
    }

    override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
      logger.info(s"WebSocket connection closed: code=$code, reason=$reason, remote=$remote")
      wsConnected.set(false)

      // Complete any pending requests with connection error
      pendingResponses.values().asScala.foreach { future =>
        if (!future.isDone) {
          future.complete(
            WorkspaceAgentErrorResponse(
              "unknown",
              "WebSocket connection closed",
              "CONNECTION_CLOSED"
            )
          )
        }
      }
      pendingResponses.clear()
    }

    override def onError(ex: Exception): Unit = {
      logger.error(s"WebSocket error: ${ex.getMessage}", ex)
      wsConnected.set(false)
    }
  }

  private def handleWebSocketMessage(message: String): Unit =
    Try(read[WebSocketMessage](message)) match {
      case Success(msg) =>
        msg match {
          case ResponseMessage(response) =>
            handleResponse(response)

          case HeartbeatResponseMessage(timestamp) =>
            logger.debug(s"Received heartbeat response at $timestamp")

          case StreamingOutputMessage(commandId, outputType, content, isComplete) =>
            handleStreamingOutput(commandId, outputType, content, isComplete)

          case CommandStartedMessage(commandId, command) =>
            logger.debug(s"Command started: $commandId - $command")

          case CommandCompletedMessage(commandId, exitCode, durationMs) =>
            logger.debug(s"Command completed: $commandId, exit=$exitCode, duration=${durationMs}ms")

          case ErrorMessage(error, code, commandId) =>
            logger.error(s"Received error message: $error (code: $code, commandId: $commandId)")
            commandId.foreach { id =>
              Option(pendingResponses.remove(id)).foreach { future =>
                future.complete(WorkspaceAgentErrorResponse(id, error, code))
              }
            }

          case _ =>
            logger.warn(s"Unexpected WebSocket message type: ${msg.getClass.getSimpleName}")
        }

      case Failure(ex) =>
        logger.error(s"Failed to parse WebSocket message: ${ex.getMessage}", ex)
    }

  private def handleResponse(response: WorkspaceAgentResponse): Unit =
    Option(pendingResponses.remove(response.commandId)) match {
      case Some(future) =>
        future.complete(response)
      case None =>
        logger.warn(s"Received response for unknown command ID: ${response.commandId}")
    }

  private def handleStreamingOutput(commandId: String, outputType: String, content: String, isComplete: Boolean): Unit =
    Option(streamingHandlers.get(commandId)) match {
      case Some(handler) =>
        Try(handler(StreamingOutputMessage(commandId, outputType, content, isComplete))).failed.foreach { ex =>
          logger.error(s"Error in streaming handler for command $commandId: ${ex.getMessage}", ex)
        }
        if (isComplete) streamingHandlers.remove(commandId)
      case None =>
        logger.debug(s"No streaming handler registered for command $commandId")
    }

  /**
   * Starts the workspace runner docker container and establishes WebSocket connection
   */
  def startContainer(): Boolean = {
    logger.info(s"Starting workspace runner container: $containerName")

    // Ensure the workspace directory exists
    val dir = new File(workspaceDir)
    if (!dir.exists()) {
      dir.mkdirs()
    }

    // Run the docker container with the workspace directory mounted
    val dockerProcess = Try {
      val pb = new java.lang.ProcessBuilder(
        "docker",
        "run",
        "-d",
        "--name",
        containerName,
        "-p",
        s"$port:8080",
        "-v",
        s"$workspaceDir:/workspace",
        "docker.io/library/workspace-runner:0.1.0-SNAPSHOT"
      )
      val process  = pb.start()
      val exitCode = process.waitFor()

      // Capture and log stdout
      val stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream))
      val stdout       = Iterator.continually(stdoutReader.readLine()).takeWhile(_ != null).mkString("\n")
      if (stdout.nonEmpty) logger.info(s"Docker stdout: $stdout")

      // Capture and log stderr
      val stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream))
      val stderr       = Iterator.continually(stderrReader.readLine()).takeWhile(_ != null).mkString("\n")
      if (stderr.nonEmpty) logger.warn(s"Docker stderr: $stderr")

      (exitCode, stdout, stderr)
    }

    dockerProcess match {
      case Success((0, containerId, _)) =>
        logger.info(s"Container $containerName started successfully with ID: ${containerId.trim}")

        // Wait until the container is responsive and establish WebSocket connection
        if (waitForContainerStartupAndConnect()) {
          containerRunning.set(true)
          startHeartbeatTask()
          true
        } else {
          logger.error("Container started but WebSocket connection failed")
          stopContainer()
          false
        }

      case Success((exitCode, _, stderr)) =>
        logger.error(s"Failed to start container, exit code: $exitCode, stderr: $stderr")
        false

      case Failure(ex) =>
        logger.error(s"Exception starting container: ${ex.getMessage}", ex)
        false
    }
  }

  private def waitForContainerStartupAndConnect(): Boolean = {
    logger.info("Waiting for container service to be ready and establishing WebSocket connection...")

    var attempts = 0
    while (attempts < MaxStartupAttempts) {
      val ok = scala.util
        .Try {
          val httpResponse = requests.get(s"http://localhost:$port/", readTimeout = 1000, connectTimeout = 1000)
          httpResponse.statusCode == 200 && connectWebSocket()
        }
        .getOrElse(false)
      if (ok) {
        logger.info("WebSocket connection established successfully")
        return true
      }

      attempts += 1
      logger.debug(s"Waiting for service to be ready (attempt $attempts/$MaxStartupAttempts)")
      Thread.sleep(1000)
    }

    logger.error(s"Service failed to respond within $MaxStartupAttempts attempts")
    false
  }

  private def connectWebSocket(): Boolean = {
    val attempt = Try {
      val client = new WorkspaceWebSocketClient(new URI(wsUrl))
      wsClient.set(client)
      val connected = client.connectBlocking(ConnectionTimeoutMs, TimeUnit.MILLISECONDS)
      connected && wsConnected.get()
    }
    attempt.recover { case ex => logger.error(s"Exception connecting WebSocket: ${ex.getMessage}", ex); false }.get
  }

  private def startHeartbeatTask(): Unit = {
    logger.info("Starting heartbeat task")

    heartbeatExecutor.scheduleAtFixedRate(
      () =>
        if (containerRunning.get() && wsConnected.get()) {
          val hb = Try(sendHeartbeat())
          hb.failed.foreach { e =>
            logger.warn(s"Failed to send heartbeat: ${e.getMessage}")
            handleContainerDown()
          }
        },
      0,
      HeartbeatIntervalSeconds,
      TimeUnit.SECONDS
    )
  }

  private def sendHeartbeat(): Unit =
    Option(wsClient.get()) match {
      case Some(client) if client.isOpen =>
        val heartbeat = HeartbeatMessage(System.currentTimeMillis())
        val json      = write(heartbeat)
        client.send(json)
        logger.debug("Heartbeat sent")
      case _ =>
        throw new RuntimeException("WebSocket client is not connected")
    }

  private def handleContainerDown(): Unit =
    if (containerRunning.compareAndSet(true, false)) {
      logger.error("Container appears to be down - WebSocket connection lost")
      wsConnected.set(false)
    }

  def stopContainer(): Boolean = {
    logger.info(s"Stopping workspace runner container: $containerName")
    containerRunning.set(false)
    wsConnected.set(false)

    // Close WebSocket connection
    Option(wsClient.get()).foreach { client =>
      scala.util
        .Try(client.close())
        .failed
        .foreach(ex => logger.error(s"Error closing WebSocket: ${ex.getMessage}", ex))
    }

    // Shutdown heartbeat task
    heartbeatExecutor.shutdown()
    val term = Try(heartbeatExecutor.awaitTermination(3, TimeUnit.SECONDS)).getOrElse(false)
    if (!term) heartbeatExecutor.shutdownNow()

    // Execute stop and remove as separate commands
    val stopResult = Try {
      val process  = Runtime.getRuntime.exec(Array("docker", "stop", containerName))
      val exitCode = process.waitFor()
      val stderr   = scala.io.Source.fromInputStream(process.getErrorStream).mkString.trim
      (exitCode, stderr)
    }

    val rmResult = stopResult match {
      case Success((0, _)) =>
        Try {
          val process  = Runtime.getRuntime.exec(Array("docker", "rm", containerName))
          val exitCode = process.waitFor()
          val stderr   = scala.io.Source.fromInputStream(process.getErrorStream).mkString.trim
          (exitCode, stderr)
        }
      case Success((_, stderr)) =>
        logger.error(s"Failed to stop container: $stderr")
        Failure(new RuntimeException(s"Container stop failed: $stderr"))
      case Failure(ex) =>
        Failure(ex)
    }

    (stopResult, rmResult) match {
      case (Success((0, _)), Success((0, _))) =>
        logger.info(s"Container $containerName stopped and removed successfully")
        true
      case (Success((0, _)), Success((_, stderr))) =>
        logger.error(s"Failed to remove container: $stderr")
        false
      case (Success((0, _)), Failure(ex)) =>
        logger.error(s"Exception removing container: ${ex.getMessage}", ex)
        false
      case _ =>
        logger.error(s"Failed to stop and remove container")
        false
    }
  }

  /**
   * Sends a command via WebSocket and waits for the response
   */
  private def sendCommand(command: WorkspaceAgentCommand): WorkspaceAgentResponse = {
    if (!wsConnected.get()) {
      throw new RuntimeException("WebSocket is not connected - cannot send command")
    }

    val future = new CompletableFuture[WorkspaceAgentResponse]()
    pendingResponses.put(command.commandId, future)

    val sendEither = for {
      json <- Try(write(CommandMessage(command))).toEither.left.map(_.getMessage)
      _ <- Option(wsClient.get()).filter(_.isOpen).toRight("WebSocket client is not connected").map { client =>
        client.send(json)
        logger.debug(s"Sent command: ${command.getClass.getSimpleName} with ID: ${command.commandId}")
      }
      response <- Try(future.get(30, TimeUnit.SECONDS)).toEither.left.map(_.getMessage)
      finalResp <- response match {
        case error: WorkspaceAgentErrorResponse => Left(s"${error.code}: ${error.error}")
        case ok                                 => Right(ok)
      }
    } yield finalResp

    sendEither.fold(
      err => {
        pendingResponses.remove(command.commandId)
        throw new WorkspaceAgentException(err, "EXECUTION_FAILED", None)
      },
      ok => ok
    )
  }

  // WorkspaceAgentInterface implementation
  override def exploreFiles(
    path: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    maxDepth: Option[Int] = None,
    returnMetadata: Option[Boolean] = None
  ): ExploreFilesResponse = {
    val command = ExploreFilesCommand(
      commandId = java.util.UUID.randomUUID().toString,
      path = path,
      recursive = recursive,
      excludePatterns = excludePatterns,
      maxDepth = maxDepth,
      returnMetadata = returnMetadata
    )
    sendCommand(command).asInstanceOf[ExploreFilesResponse]
  }

  override def readFile(
    path: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None
  ): ReadFileResponse = {
    val command = ReadFileCommand(
      commandId = java.util.UUID.randomUUID().toString,
      path = path,
      startLine = startLine,
      endLine = endLine
    )
    sendCommand(command).asInstanceOf[ReadFileResponse]
  }

  override def writeFile(
    path: String,
    content: String,
    mode: Option[String] = None,
    createDirectories: Option[Boolean] = None
  ): WriteFileResponse = {
    val command = WriteFileCommand(
      commandId = java.util.UUID.randomUUID().toString,
      path = path,
      content = content,
      mode = mode,
      createDirectories = createDirectories
    )
    sendCommand(command).asInstanceOf[WriteFileResponse]
  }

  override def modifyFile(
    path: String,
    operations: List[FileOperation]
  ): ModifyFileResponse = {
    val command = ModifyFileCommand(
      commandId = java.util.UUID.randomUUID().toString,
      path = path,
      operations = operations
    )
    sendCommand(command).asInstanceOf[ModifyFileResponse]
  }

  override def searchFiles(
    paths: List[String],
    query: String,
    searchType: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    contextLines: Option[Int] = None
  ): SearchFilesResponse = {
    val command = SearchFilesCommand(
      commandId = java.util.UUID.randomUUID().toString,
      paths = paths,
      query = query,
      `type` = searchType,
      recursive = recursive,
      excludePatterns = excludePatterns,
      contextLines = contextLines
    )
    sendCommand(command).asInstanceOf[SearchFilesResponse]
  }

  override def executeCommand(
    command: String,
    workingDirectory: Option[String] = None,
    timeout: Option[Int] = None,
    environment: Option[Map[String, String]] = None
  ): ExecuteCommandResponse = {
    val cmd = ExecuteCommandCommand(
      commandId = java.util.UUID.randomUUID().toString,
      command = command,
      workingDirectory = workingDirectory,
      timeout = timeout,
      environment = environment
    )
    sendCommand(cmd).asInstanceOf[ExecuteCommandResponse]
  }

  override def getWorkspaceInfo(): GetWorkspaceInfoResponse = {
    val command = GetWorkspaceInfoCommand(
      commandId = java.util.UUID.randomUUID().toString
    )
    sendCommand(command).asInstanceOf[GetWorkspaceInfoResponse]
  }

  /**
   * Execute a command with streaming output support.
   * The handler will be called for each chunk of output received.
   */
  def executeCommandWithStreaming(
    command: String,
    workingDirectory: Option[String] = None,
    timeout: Option[Int] = None,
    environment: Option[Map[String, String]] = None,
    outputHandler: StreamingOutputMessage => Unit = _ => ()
  ): ExecuteCommandResponse = {
    val cmd = ExecuteCommandCommand(
      commandId = java.util.UUID.randomUUID().toString,
      command = command,
      workingDirectory = workingDirectory,
      timeout = timeout,
      environment = environment
    )

    // Register streaming handler
    streamingHandlers.put(cmd.commandId, outputHandler)

    val resp = Try(sendCommand(cmd).asInstanceOf[ExecuteCommandResponse]).toEither
    streamingHandlers.remove(cmd.commandId)
    resp.fold(
      e => throw e,
      ok => ok
    )
  }
}
