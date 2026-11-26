package org.llm4s.workspace

import scala.concurrent.duration.FiniteDuration

import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ServerHandshake
import org.llm4s.shared._
import org.slf4j.LoggerFactory
import upickle.default._

import java.io.{ BufferedReader, File, InputStreamReader }
import java.net.URI
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong, AtomicReference }
import java.util.concurrent.{ CompletableFuture, ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import scala.util.Using
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

/**
 * WebSocket-based implementation of ContainerisedWorkspace that communicates with
 * the workspace runner via WebSocket instead of HTTP.
 *
 * This solves the threading issues of the HTTP version by:
 * - Using asynchronous WebSocket communication
 * - Implementing heartbeats over the same connection
 * - Supporting real-time streaming of command output
 * - Eliminating thread pool blocking during long commands
 *
 * Commands run without a shell, and only programs on the runner's allowlist run (#1756): `sandboxConfig`, when given,
 * chooses the list (and the paths, limits and network a command gets, design §4.39); otherwise the runner's
 * `WORKSPACE_SANDBOX_PROFILE` (unset, `permissive`) does. `extraAllowedCommands` adds to either.
 *
 * Only this client can drive the runner (#3046): the container's port is published on the host's loopback only
 * (`127.0.0.1:<hostPort>`), and every WebSocket handshake carries a random token made for this container and passed to
 * it in its environment, never on a command line. The runner refuses a handshake without it, and any browser's.
 *
 * The container never outlives this client for long (#3053): it starts with `--rm --init`, a failed start removes
 * whatever it left, [[stopContainer]] (or [[close]]) removes it even when `docker stop` fails, a JVM shutdown hook does
 * the same when the JVM exits without calling either, and the runner exits by itself once no client has been
 * connected for `idleTimeout`. A dropped connection (a long pause of this JVM, which the runner ends after 30 seconds
 * without a message, or a runner that stops answering heartbeats) is reconnected with backoff; when that fails the
 * container is removed and every later call throws a [[org.llm4s.shared.WorkspaceAgentException WorkspaceAgentException]]
 * with code `WORKSPACE_UNAVAILABLE`. A command in flight when the connection dropped fails `CONNECTION_CLOSED`: the
 * runner kills a closed connection's commands, and a file change's outcome is then unknown.
 *
 * @param workspaceDir         the host directory mounted as the container's `/workspace`
 * @param imageName            the workspace-runner image
 * @param hostPort             the host port the container's 8080 is published on, on `127.0.0.1` only
 * @param extraAllowedCommands programs the runner may run besides its profile's allowlist (for example `sbt`),
 *                             passed to the container as `WORKSPACE_EXTRA_COMMANDS`; each is a bare program name,
 *                             never a shell or a launcher (see [[org.llm4s.shared.WorkspaceSandboxConfig.withExtraCommands WorkspaceSandboxConfig.withExtraCommands]]), and
 *                             a container is not started with a name the runner would refuse
 * @param sandboxConfig        the runner's whole sandbox config, passed as `WORKSPACE_SANDBOX_CONFIG`; `None` leaves
 *                             the runner on its profile. With `networkAllowed = false` (the default, and the
 *                             permissive profile's) the container itself has no egress (#870): it starts with
 *                             `--cap-add NET_ADMIN -e LLM4S_NO_EGRESS=1`, and the image's entry point lets it answer the
 *                             published port but open no connection out, then drops `NET_ADMIN` before the runner
 *                             starts. A Docker host where the entry point cannot set that up does not start the container
 * @param commandCgroups       start the container so the runner can give each command a cgroup of its own and kill it
 *                             whole, so a daemon a command starts (`setsid`) cannot outlive it (design §4.39, #2008):
 *                             `--cgroupns=private --cap-add SYS_ADMIN --security-opt apparmor=unconfined`. The image's
 *                             entry point uses `SYS_ADMIN` only to make the container's cgroup writable, and drops it
 *                             before the runner starts. Off by default: commands' daemons can then outlive them, as the
 *                             runner logs at startup
 * @param idleTimeout          how long the runner waits with no client connected before it exits, and its container
 *                             is removed (#3053); rounded up to whole seconds. Longer than this client's reconnection
 *                             (about 20 seconds), so a reconnecting client finds the runner still there
 */
class ContainerisedWorkspace(
  val workspaceDir: String,
  val imageName: String,
  val hostPort: Int,
  val extraAllowedCommands: Set[String] = Set.empty,
  val sandboxConfig: Option[WorkspaceSandboxConfig] = None,
  val commandCgroups: Boolean = false,
  val idleTimeout: FiniteDuration = ContainerisedWorkspace.DefaultIdleTimeout
) extends WorkspaceAgentInterface
    with AutoCloseable {
  import ContainerisedWorkspace.{ DockerCli, Pending }

  private val logger = LoggerFactory.getLogger(getClass)
  // the container's name, which the integration suites `docker exec` into
  private[workspace] val containerName = s"workspace-runner-${java.util.UUID.randomUUID().toString}"
  private val containerPort            = 8080
  // 127.0.0.1, not localhost: the port is published on the IPv4 loopback only, and localhost may resolve to ::1
  private val wsUrl = s"ws://127.0.0.1:$hostPort/ws"
  // this container's token (#3046), passed to it in its environment and sent on every handshake
  private val token = RunnerHandshake.newToken()

  // Constants
  private val MaxStartupAttempts       = 10
  private val ConnectionTimeoutMs      = 10000L
  private val DefaultCommandTimeoutSec = 30
  private val ResponseTimeoutBufferSec = 5

  /** How `docker` is run; a spec replaces it to start no container. */
  private[workspace] def docker: DockerCli = DockerCli.Process

  /** How often a heartbeat is sent; the runner ends a connection after 30 seconds without a message. */
  private[workspace] def heartbeatInterval: FiniteDuration = 5.seconds

  /** How long the runner may leave heartbeats unanswered before the connection is dropped and reconnected. */
  private[workspace] def heartbeatResponseTimeout: FiniteDuration = 20.seconds

  /** The waits before each reconnection attempt after the connection dropped: about 16 seconds in all. */
  private[workspace] def reconnectDelays: Seq[FiniteDuration] =
    Seq(0.millis, 250.millis, 500.millis, 1.second, 2.seconds, 4.seconds, 8.seconds)

  // State tracking: running from a successful start until stopped, or until a reconnection failed
  private val containerRunning      = new AtomicBoolean(false)
  private val heartbeatExecutor     = new AtomicReference[ScheduledExecutorService]()
  private val heartbeatThread       = new AtomicReference[Thread]()
  private val lastHeartbeatResponse = new AtomicLong(0L)
  private val reconnectLock         = new Object
  private val shutdownHook          = new AtomicReference[Thread]()

  // WebSocket client and response handling
  private val wsClient = new AtomicReference[WorkspaceWebSocketClient]()
  // by command id, each with the connection it was sent on, which fails it when it closes
  private val pendingResponses = new ConcurrentHashMap[String, Pending]()
  // by command id, from executeCommandWithStreaming's send until its response; never removed by a stream's own end
  private[workspace] val streamingHandlers = new ConcurrentHashMap[String, StreamingOutputMessage => Unit]()

  /**
   * Custom WebSocket client that handles workspace agent protocol. Its handshake carries the container's token.
   */
  private class WorkspaceWebSocketClient(serverUri: URI)
      extends WebSocketClient(
        serverUri,
        Map(RunnerHandshake.AuthorizationHeader -> RunnerHandshake.authorization(token)).asJava
      ) {

    override def onOpen(handshake: ServerHandshake): Unit =
      logger.info("WebSocket connection opened to workspace runner")

    override def onMessage(message: String): Unit = {
      logger.debug(s"Received WebSocket message: $message")
      handleWebSocketMessage(message)
    }

    override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
      logger.info(s"WebSocket connection closed: code=$code, reason=$reason, remote=$remote")
      // a command sent on this connection gets no response now: the runner kills a closed connection's commands
      failPending(this)
      if (containerRunning.get() && (wsClient.get() eq this))
        logger.warn("Lost the connection to the workspace runner; reconnecting (#3053)")
    }

    override def onError(ex: Exception): Unit =
      logger.error(s"WebSocket error: ${ex.getMessage}", ex)
  }

  private def failPending(client: WebSocketClient): Unit =
    pendingResponses.asScala.foreach { case (id, pending) =>
      if ((pending.client eq client) && pendingResponses.remove(id, pending))
        pending.future.complete(
          WorkspaceAgentErrorResponse(
            id,
            "WebSocket connection closed before the response: the command's outcome is unknown",
            "CONNECTION_CLOSED"
          )
        ): Unit
    }

  private def handleWebSocketMessage(message: String): Unit =
    Try(read[WebSocketMessage](message)) match {
      case Success(msg) =>
        msg match {
          case ResponseMessage(response) =>
            handleResponse(response)

          case HeartbeatResponseMessage(timestamp) =>
            lastHeartbeatResponse.set(System.nanoTime())
            logger.debug(s"Received heartbeat response at $timestamp")

          case StreamingOutputMessage(commandId, outputType, content, isComplete) =>
            handleStreamingOutput(commandId, outputType, content, isComplete)

          case CommandStartedMessage(commandId, command) =>
            logger.debug(s"Command started: $commandId - $command")

          case CommandCompletedMessage(commandId, exitCode, duration) =>
            logger.debug(s"Command completed: $commandId, exit=$exitCode, duration=${duration.toMillis}ms")

          case ErrorMessage(error, code, commandId) =>
            logger.error(s"Received error message: $error (code: $code, commandId: $commandId)")
            commandId.foreach { id =>
              Option(pendingResponses.remove(id)).foreach { pending =>
                pending.future.complete(WorkspaceAgentErrorResponse(id, error, code))
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
      case Some(pending) =>
        pending.future.complete(response): Unit
      case None =>
        logger.warn(s"Received response for unknown command ID: ${response.commandId}")
    }

  /**
   * Passes a chunk to its command's handler. Each of stdout and stderr ends with its own `isComplete` message, in
   * either order, so neither may remove the handler: `executeCommandWithStreaming` removes it once the response is in.
   */
  private[workspace] def handleStreamingOutput(
    commandId: String,
    outputType: String,
    content: String,
    isComplete: Boolean
  ): Unit =
    Option(streamingHandlers.get(commandId)) match {
      case Some(handler) =>
        Try(handler(StreamingOutputMessage(commandId, outputType, content, isComplete))).failed.foreach { ex =>
          logger.error(s"Error in streaming handler for command $commandId: ${ex.getMessage}", ex)
        }
      case None =>
        logger.debug(s"No streaming handler registered for command $commandId")
    }

  /**
   * Starts the workspace runner docker container and establishes WebSocket connection. A start that fails removes
   * whatever container it left (#3053).
   */
  def startContainer(): Boolean = {
    logger.info(s"Starting workspace runner container: $containerName (hostPort=$hostPort, image=$imageName)")

    // Ensure the workspace directory exists
    val dir = new File(workspaceDir)
    if (!dir.exists()) {
      dir.mkdirs()
    }

    // Run the docker container with the workspace directory mounted
    val runArgs = ContainerisedWorkspace.dockerRunArgs(
      containerName,
      hostPort,
      containerPort,
      workspaceDir,
      imageName,
      extraAllowedCommands,
      sandboxConfig,
      commandCgroups,
      idleTimeout
    )

    runArgs match {
      case Left(msg) =>
        logger.error(s"Not starting the container: sandbox config: $msg")
        false
      case Right(args) =>
        // from here a container may exist: removed when this JVM exits, unless stopped first
        addShutdownHook()
        // the token by the docker CLI's environment (`-e NAME` with no value), so no command line shows it
        started(docker.run(args, Map(RunnerHandshake.TokenEnvVar -> token)))
    }
  }

  private def started(dockerProcess: Try[(Int, String, String)]): Boolean =
    dockerProcess match {
      case Success((0, containerId, _)) =>
        logger.info(s"Container $containerName started successfully with ID: ${containerId.trim}")
        containerRunning.set(true)

        // Wait until the container is responsive and establish WebSocket connection
        if (waitForContainerStartupAndConnect()) {
          startHeartbeatTask()
          true
        } else {
          logger.error("Container started but WebSocket connection failed")
          stopContainer()
          false
        }

      case Success((exitCode, _, stderr)) =>
        // `docker run -d --name X -p <busy port>` leaves X created but not started
        logger.error(s"Failed to start container, exit code: $exitCode, stderr: $stderr")
        stopContainer()
        false

      case Failure(ex) =>
        logger.error(s"Exception starting container: ${ex.getMessage}", ex)
        stopContainer()
        false
    }

  private def waitForContainerStartupAndConnect(): Boolean = {
    logger.info("Waiting for container service to be ready and establishing WebSocket connection...")

    var attempts = 0
    // a stop while starting ends the wait
    while (attempts < MaxStartupAttempts && containerRunning.get()) {
      if (connectWebSocket()) {
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
      val connected =
        client.connectBlocking(ConnectionTimeoutMs, TimeUnit.MILLISECONDS) && client.isOpen
      if (connected) {
        lastHeartbeatResponse.set(System.nanoTime())
        Option(wsClient.getAndSet(client)).foreach(old => Try(old.close()))
      } else Try(client.close())
      connected
    }
    attempt.recover { case ex => logger.debug(s"Could not connect the WebSocket: ${ex.getMessage}", ex); false }.get
  }

  /**
   * The open connection to the runner: the current one, or a new one after it dropped, tried with backoff. When no
   * connection can be made the runner is gone or hung: the container is removed, and the workspace fails for good with
   * `WORKSPACE_UNAVAILABLE`.
   */
  private def ensureConnected(): Either[WorkspaceAgentException, WebSocketClient] = {
    def notRunning = new WorkspaceAgentException(
      "The workspace is not running: it was never started, or was stopped",
      "WORKSPACE_NOT_RUNNING",
      None
    )
    reconnectLock.synchronized {
      Option(wsClient.get()).filter(_.isOpen) match {
        case Some(open)                      => Right(open)
        case None if !containerRunning.get() => Left(notRunning)
        case None =>
          val connected = reconnectDelays.iterator
            .map { delay =>
              ContainerisedWorkspace.catchingInterrupts.withTry(Thread.sleep(delay.toMillis)) match {
                case Failure(_: InterruptedException) =>
                  Thread.currentThread().interrupt()
                  Some(Left(new WorkspaceAgentException("interrupted while reconnecting", "EXECUTION_FAILED", None)))
                case _ if !containerRunning.get() => Some(Left(notRunning))
                case _ if connectWebSocket() =>
                  logger.info("Reconnected to the workspace runner")
                  Some(Right(wsClient.get(): WebSocketClient))
                case _ => None
              }
            }
            .collectFirst { case Some(result) => result }
          connected.getOrElse {
            logger.error(
              s"Could not reconnect to the workspace runner after ${reconnectDelays.size} attempts; removing the " +
                s"container $containerName (#3053)"
            )
            stopContainer(): Unit
            Left(
              new WorkspaceAgentException(
                s"Lost the connection to the workspace runner and could not reconnect; the container $containerName " +
                  "is removed",
                "WORKSPACE_UNAVAILABLE",
                None
              )
            )
          }
      }
    }
  }

  private def startHeartbeatTask(): Unit = {
    logger.info("Starting heartbeat task")

    val executor = Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
      val t = new Thread(r, s"llm4s-workspace-heartbeat-$containerName")
      t.setDaemon(true)
      heartbeatThread.set(t)
      t
    }
    heartbeatExecutor.set(executor)
    executor.scheduleWithFixedDelay(
      () =>
        if (containerRunning.get()) Try(heartbeat()).failed.foreach(e => logger.warn(s"Heartbeat: ${e.getMessage}")),
      0,
      heartbeatInterval.toMillis,
      TimeUnit.MILLISECONDS
    ): Unit
  }

  /**
   * One heartbeat: sent on an open connection; a connection whose heartbeats the runner has left unanswered too long
   * (a hung runner) is dropped; a dropped one is reconnected.
   */
  private def heartbeat(): Unit =
    Option(wsClient.get()).filter(_.isOpen) match {
      case Some(client) if System.nanoTime() - lastHeartbeatResponse.get() > heartbeatResponseTimeout.toNanos =>
        logger.warn(s"The workspace runner has answered no heartbeat for $heartbeatResponseTimeout; reconnecting")
        client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "no heartbeat response")
      case Some(client) =>
        client.send(write(HeartbeatMessage(System.currentTimeMillis())))
        logger.debug("Heartbeat sent")
      case None =>
        ensureConnected().left.foreach(e => logger.error(e.error))
    }

  private def addShutdownHook(): Unit = {
    val hook = new Thread(
      () => {
        logger.info(s"JVM shutting down: removing the workspace container $containerName")
        stopContainer(): Unit
      },
      s"llm4s-workspace-shutdown-$containerName"
    )
    if (shutdownHook.compareAndSet(null, hook)) Runtime.getRuntime.addShutdownHook(hook)
  }

  private def removeShutdownHook(): Unit =
    Option(shutdownHook.getAndSet(null)).foreach { hook =>
      // not from the hook itself, and not once the JVM is shutting down (which throws)
      if (Thread.currentThread() ne hook) Try(Runtime.getRuntime.removeShutdownHook(hook)): Unit
    }

  /**
   * Stops and removes the container: `docker stop`, then `docker rm -f` whatever `stop` did, so a container that
   * would not stop is still removed. `true` when the container is gone (or never existed). Safe to call more than once.
   */
  def stopContainer(): Boolean = {
    logger.info(s"Stopping workspace runner container: $containerName")
    containerRunning.set(false)

    // Close WebSocket connection
    Option(wsClient.getAndSet(null)).foreach { client =>
      scala.util
        .Try(client.close())
        .failed
        .foreach(ex => logger.error(s"Error closing WebSocket: ${ex.getMessage}", ex))
    }

    // Shutdown heartbeat task; not waited for from its own thread (a reconnection that failed stops the container)
    Option(heartbeatExecutor.getAndSet(null)).foreach { executor =>
      executor.shutdown()
      if (Thread.currentThread() ne heartbeatThread.get()) {
        val term = ContainerisedWorkspace.catchingInterrupts
          .withTry(executor.awaitTermination(3, TimeUnit.SECONDS))
          .getOrElse(false)
        if (!term) executor.shutdownNow(): Unit
      }
    }

    removeShutdownHook()

    // `stop` lets the runner end its commands; `rm -f` removes the container whether or not it stopped. With --rm,
    // a stopped container is already gone, or going
    val stopResult = docker.run(Seq("docker", "stop", "-t", "5", containerName), Map.empty)
    stopResult match {
      case Success((0, _, _))      => ()
      case Success((_, _, stderr)) => logger.debug(s"docker stop $containerName: $stderr")
      case Failure(ex)             => logger.debug(s"docker stop $containerName: ${ex.getMessage}")
    }
    docker.run(Seq("docker", "rm", "-f", containerName), Map.empty) match {
      case Success((0, _, _)) =>
        logger.info(s"Container $containerName stopped and removed successfully")
        true
      case Success((_, _, stderr)) if ContainerisedWorkspace.alreadyGone(stderr) =>
        logger.info(s"Container $containerName is removed")
        true
      case Success((_, _, stderr)) =>
        logger.error(s"Failed to remove container $containerName: $stderr")
        false
      case Failure(ex) =>
        logger.error(s"Exception removing container $containerName: ${ex.getMessage}", ex)
        false
    }
  }

  /** Stops and removes the container, as [[stopContainer]] does. */
  override def close(): Unit = stopContainer(): Unit

  /**
   * Sends a command via WebSocket and waits for the response. A read (it changes nothing) whose connection closed
   * before its response is sent once more, on a new connection; a change or an execute command is not, since it may
   * have run.
   */
  private def sendCommand(command: WorkspaceAgentCommand): WorkspaceAgentResponse = {
    val readOnly = command match {
      case _: ExploreFilesCommand | _: ReadFileCommand | _: SearchFilesCommand | _: FileSnapshotCommand |
          _: GetWorkspaceInfoCommand =>
        true
      case _ => false
    }
    Try(sendOnce(command)) match {
      case Failure(e: WorkspaceAgentException)
          if e.code == ContainerisedWorkspace.NotSentCode || (readOnly && e.code == "CONNECTION_CLOSED") =>
        logger.info(s"Sending ${command.getClass.getSimpleName} ${command.commandId} again: its connection closed")
        sendOnce(command)
      case other => other.get
    }
  }

  private def sendOnce(command: WorkspaceAgentCommand): WorkspaceAgentResponse = {
    val client = ensureConnected().fold(e => throw e, identity)

    val future = new CompletableFuture[WorkspaceAgentResponse]()
    pendingResponses.put(command.commandId, Pending(client, future))
    // closed since `ensureConnected`: its close may have failed the pending commands before this one was added
    if (!client.isOpen) failPending(client)

    val sendEither = for {
      json <- Try(write(CommandMessage(command))).toEither.left.map(_.getMessage)
      _ = Try(client.send(json)).failed.foreach { e =>
        // never reached the runner, so it may be sent again on a new connection
        pendingResponses.remove(command.commandId)
        throw new WorkspaceAgentException(
          s"WebSocket connection closed before the command was sent (${e.getMessage})",
          ContainerisedWorkspace.NotSentCode,
          None
        )
      }
      _ = logger.debug(s"Sent command: ${command.getClass.getSimpleName} with ID: ${command.commandId}")
      // every way the wait can end without a response - a timeout, a failure, or an interrupt (a cancelled agent run;
      // `Try` would let that one escape the cleanup below) - is a give-up, which cancels the command
      response <- awaitResponse(command, future).toEither.left
        .map {
          case _: InterruptedException =>
            Thread.currentThread().interrupt() // kept for the caller, which is being cancelled
            "interrupted while waiting for the response"
          case e => Option(e.getMessage).getOrElse(e.toString)
        }
    } yield response

    sendEither.fold(
      err => {
        pendingResponses.remove(command.commandId)
        // a command this client gave up waiting for is cancelled, however late the runner would have started it (a
        // slow preparation, a wait at the gate): its outcome stays unknown to the caller, but it never runs on unseen
        command match {
          case exec: ExecuteCommandCommand =>
            Try(write[WebSocketMessage](CancelCommandMessage(exec.commandId))).foreach { json =>
              Some(client).filter(_.isOpen).foreach(c => Try(c.send(json)))
            }
          case _ => ()
        }
        throw new WorkspaceAgentException(err, "EXECUTION_FAILED", None)
      },
      {
        // the runner's own code, as WorkspaceAgentInterfaceRemote keeps it: a caller tells a refusal (a path
        // not found, a compare-and-set that lost) from a failure by it
        case error: WorkspaceAgentErrorResponse =>
          throw new WorkspaceAgentException(error.error, error.code, error.details)
        case ok => ok
      }
    )
  }

  /**
   * The response to `command`, waited for up to its deadline. A command is given up on when interrupted (and then
   * cancelled). A file change is not: it has no cancellation, so an interrupt (a cancelled agent run) waits on for the
   * response, which the runner sends by its commit deadline, before this deadline, so the caller never stops waiting
   * while a change it cannot see may still commit. The interrupt is kept for the caller either way.
   */
  private[workspace] def awaitResponse(
    command: WorkspaceAgentCommand,
    future: CompletableFuture[WorkspaceAgentResponse]
  ): Try[WorkspaceAgentResponse] = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(responseTimeoutSeconds(command))
    // only a change waits out an interrupt: a read commits nothing, so abandoning its response is safe and prompt
    val cancellable = command match {
      case _: WriteFileCommand | _: ModifyFileCommand | _: DeleteFileCommand => false
      case _                                                                 => true
    }
    @scala.annotation.tailrec
    def await(interrupted: Boolean): (Try[WorkspaceAgentResponse], Boolean) =
      ContainerisedWorkspace.catchingInterrupts.withTry(
        future.get(math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
      ) match {
        case scala.util.Failure(_: InterruptedException) if !cancellable => await(interrupted = true)
        case other                                                       => (other, interrupted)
      }
    val (result, interrupted) = await(interrupted = false)
    if (interrupted) Thread.currentThread().interrupt()
    result
  }

  // every request may first wait at the runner's command gate, which then refuses rather than run late
  private def responseTimeoutSeconds(command: WorkspaceAgentCommand): Long = {
    val gateSec = WireDurations.toWholeSeconds(WorkspaceSandboxConfig.CommandGateWait)
    command match {
      case exec: ExecuteCommandCommand =>
        val commandTimeoutSec =
          exec.timeout.map(WireDurations.toWholeSeconds).getOrElse(DefaultCommandTimeoutSec.toLong)
        gateSec + commandTimeoutSec + ResponseTimeoutBufferSec
      case _ =>
        // longer than the runner lets a file change take before it must commit, so the runner refuses an overdue
        // change before this client gives up on it
        gateSec + WireDurations.toWholeSeconds(WorkspaceSandboxConfig.CommitAfterGate) + ResponseTimeoutBufferSec
    }
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
    createDirectories: Option[Boolean] = None,
    expectedHash: Option[String] = None
  ): WriteFileResponse = {
    val command = WriteFileCommand(
      commandId = java.util.UUID.randomUUID().toString,
      path = path,
      content = content,
      mode = mode,
      createDirectories = createDirectories,
      expectedHash = expectedHash
    )
    sendCommand(command).asInstanceOf[WriteFileResponse]
  }

  override def fileSnapshot(
    path: String,
    includeContent: Boolean = true,
    followLinks: Boolean = true
  ): FileSnapshotResponse =
    sendCommand(FileSnapshotCommand(java.util.UUID.randomUUID().toString, path, includeContent, followLinks))
      .asInstanceOf[FileSnapshotResponse]

  override def deleteFile(path: String, expectedHash: Option[String] = None): DeleteFileResponse =
    sendCommand(DeleteFileCommand(java.util.UUID.randomUUID().toString, path, expectedHash))
      .asInstanceOf[DeleteFileResponse]

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
    timeout: Option[FiniteDuration] = None,
    environment: Option[Map[String, String]] = None,
    maxOutputBytes: Option[Long] = None
  ): ExecuteCommandResponse = {
    val cmd = ExecuteCommandCommand(
      commandId = java.util.UUID.randomUUID().toString,
      command = command,
      workingDirectory = workingDirectory,
      timeout = timeout,
      environment = environment,
      maxOutputBytes = maxOutputBytes
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
    timeout: Option[FiniteDuration] = None,
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

object ContainerisedWorkspace {

  /** How long the runner waits with no client connected before it exits, unless a workspace sets its own (#3053). */
  val DefaultIdleTimeout: FiniteDuration = 5.minutes

  /** The code of a command whose connection closed before it was sent: it never reached the runner. */
  private[workspace] val NotSentCode: String = "NOT_SENT"

  /** A command sent and its response's future, with the connection it went on, which fails it when it closes. */
  final private case class Pending(client: WebSocketClient, future: CompletableFuture[WorkspaceAgentResponse])

  /** How the client runs the `docker` CLI: its exit code, standard output and standard error. */
  private[workspace] trait DockerCli {
    def run(args: Seq[String], environment: Map[String, String]): Try[(Int, String, String)]
  }

  private[workspace] object DockerCli {

    /** The `docker` CLI, as a process. */
    object Process extends DockerCli {
      private val logger = LoggerFactory.getLogger(getClass)

      def run(args: Seq[String], environment: Map[String, String]): Try[(Int, String, String)] = Try {
        val pb = new java.lang.ProcessBuilder(args.asJava)
        environment.foreach { case (k, v) => pb.environment().put(k, v) }
        val process = pb.start()

        // Capture stdout and stderr while process is running(before waitFor)
        val stdoutF = Future {
          Using.resource(new BufferedReader(new InputStreamReader(process.getInputStream))) { reader =>
            Iterator.continually(reader.readLine()).takeWhile(_ != null).mkString("\n")
          }
        }

        val stderrF = Future {
          Using.resource(new BufferedReader(new InputStreamReader(process.getErrorStream))) { reader =>
            Iterator.continually(reader.readLine()).takeWhile(_ != null).mkString("\n")
          }
        }

        val exitCode = process.waitFor()
        val stdout   = Await.result(stdoutF, 30.seconds)
        val stderr   = Await.result(stderrF, 30.seconds)

        if (stdout.nonEmpty) logger.debug(s"${args.take(2).mkString(" ")} stdout: $stdout")
        if (stderr.nonEmpty) logger.debug(s"${args.take(2).mkString(" ")} stderr: $stderr")

        (exitCode, stdout, stderr)
      }
    }
  }

  /** Whether `docker rm -f`'s error says the container is already removed, or being removed (`--rm`). */
  private[workspace] def alreadyGone(stderr: String): Boolean = {
    val s = stderr.toLowerCase(java.util.Locale.ROOT)
    s.contains("no such container") || s.contains("already in progress")
  }

  /**
   * Catches every failure of a wait, an interrupt among them: `allCatch` rethrows an `InterruptedException`
   * (`Exception.shouldRethrow`), which would skip the cancellation of the command a caller gave up on.
   */
  private val catchingInterrupts: scala.util.control.Exception.Catch[Nothing] =
    new scala.util.control.Exception.Catch(
      scala.util.control.Exception.allCatcher[Nothing],
      None,
      _.isInstanceOf[scala.util.control.ControlThrowable]
    )

  /**
   * What `docker run` needs for a cgroup per command (design §4.39, #2008): a private cgroup namespace, and for the
   * image's entry point `SYS_ADMIN`, without AppArmor's `docker-default` (which refuses every mount), to remount the
   * container's own cgroup read-write. The entry point drops `SYS_ADMIN` before it starts the runner.
   */
  private[workspace] val CommandCgroupsArgs: Seq[String] =
    Seq("--cgroupns=private", "--cap-add", "SYS_ADMIN", "--security-opt", "apparmor=unconfined")

  /** The image's entry point's environment variable asking it to block the container's egress (#870). */
  private[workspace] val NoEgressEnvVar: String = "LLM4S_NO_EGRESS"

  /**
   * What `docker run` needs for a container with no egress when the sandbox allows commands no network (#870):
   * `NET_ADMIN`, for the image's entry point to set the container's firewall (only connections the published port
   * accepted, and loopback, may send), and the variable asking it to. The entry point drops `NET_ADMIN` before it starts
   * the runner, and refuses to start it when the firewall cannot be set.
   */
  private[workspace] val NoEgressArgs: Seq[String] = Seq("--cap-add", "NET_ADMIN", "-e", s"$NoEgressEnvVar=1")

  /**
   * The `docker run` command that starts a workspace-runner container.
   *
   * @return the argument vector, or why the sandbox config or `extraAllowedCommands` is one the runner would refuse
   */
  private[workspace] def dockerRunArgs(
    containerName: String,
    hostPort: Int,
    containerPort: Int,
    workspaceDir: String,
    imageName: String,
    extraAllowedCommands: Set[String],
    sandboxConfig: Option[WorkspaceSandboxConfig],
    commandCgroups: Boolean = false,
    idleTimeout: FiniteDuration = DefaultIdleTimeout
  ): Either[String, Seq[String]] = {
    val extra = extraAllowedCommands.toSeq.sorted.mkString(",")
    for {
      _ <- sandboxConfig.fold[Either[String, Unit]](Right(()))(WorkspaceSandboxConfig.validate)
      _ <- sandboxConfig.getOrElse(WorkspaceSandboxConfig.Permissive).withExtraCommands(extra)
    } yield {
      val extraEnv =
        if (extra.isEmpty) Seq.empty
        else Seq("-e", s"${WorkspaceSandboxConfig.ExtraCommandsEnvVar}=$extra")
      val configEnv =
        sandboxConfig.toSeq.flatMap(c =>
          Seq("-e", s"${WorkspaceSandboxConfig.ConfigEnvVar}=${upickle.default.write(c)}")
        )
      // the runner's own default, the permissive profile, allows no network either
      val noEgress = !sandboxConfig.getOrElse(WorkspaceSandboxConfig.Permissive).networkAllowed
      // --rm: a stopped container (its runner idle, or killed) is removed; --init: a signal reaches the runner, and
      // its commands' orphans are reaped. The port on the host's loopback only (#3046); the token by name only, its
      // value in the docker CLI's environment, so no command line shows it
      val idleSeconds = math.max(1L, WireDurations.toWholeSeconds(idleTimeout))
      Seq(
        "docker",
        "run",
        "-d",
        "--rm",
        "--init",
        "--name",
        containerName,
        "-p",
        s"127.0.0.1:$hostPort:$containerPort"
      ) ++
        (if (commandCgroups) CommandCgroupsArgs else Nil) ++ (if (noEgress) NoEgressArgs else Nil) ++
        Seq("-e", RunnerHandshake.TokenEnvVar, "-e", s"${RunnerHandshake.IdleTimeoutEnvVar}=$idleSeconds") ++
        extraEnv ++ configEnv ++ Seq(
          "-v",
          s"$workspaceDir:/workspace",
          imageName
        )
    }
  }
}
