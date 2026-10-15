// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch, DisableSyntax.NoKeywordFinally
package org.llm4s.runner

import org.llm4s.shared._
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ ConcurrentHashMap, TimeUnit }
import scala.concurrent.duration.{ DurationInt, DurationLong, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

/**
 * Runs the WebSocket protocol's `ExecuteCommandCommand`, streaming the process's output as it arrives.
 *
 * The command is checked and built by [[WorkspaceAgentInterfaceImpl.prepareCommand]], the same function the direct
 * `executeCommand` uses, so both paths share the tokenizer, the `allowedCommands` allowlist, the forbidden
 * characters, [[CommandPolicy]] (options, paths, environment, git confinement, the Windows rules), the working
 * directory checks, the null-device standard input and argv execution with no shell (#1756). A refusal is sent as a
 * `WorkspaceAgentErrorResponse` with the direct path's code, followed by a `CommandCompletedMessage` with exit code 1.
 *
 * What this adds over the direct path is the protocol: `CommandStartedMessage`, stdout/stderr
 * `StreamingOutputMessage` chunks, the final `ExecuteCommandResponse`, `CommandCompletedMessage` with the exit code
 * and duration, and cancellation through [[cancel]].
 *
 * @param workspace     checks and builds every command
 * @param maxOutputSize the most characters of each stream kept for the final `ExecuteCommandResponse`, and the upper
 *                      bound on a command's `maxOutputChars`; chunks stream regardless
 */
final private[runner] class WebSocketCommandExecutor(
  workspace: WorkspaceAgentInterfaceImpl,
  maxOutputSize: Long
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Check, start and stream one command.
   *
   * @param cmd       the command from the client
   * @param owner     the client (its connection): the owner its process is registered under, which [[cancel]] and a
   *                  disconnect act on
   * @param send      delivers a protocol message to the client
   * @param connected whether the client's connection is still open: a command whose connection closed while it waited at
   *                  the gate never starts, as the close handler cancels only what it can find
   * @param cancelledEarly this client's cancellations of commands not yet registered (waiting at the gate), which
   *                  therefore never start; one set per connection, shared with [[cancel]]
   */
  def execute(
    cmd: ExecuteCommandCommand,
    owner: AnyRef,
    send: WebSocketMessage => Unit,
    connected: () => Boolean = () => true,
    cancelledEarly: EarlyCancellations = new EarlyCancellations()
  ): Unit = {
    val startTime = System.currentTimeMillis()

    def fail(error: String, code: String, details: Option[String]): Unit = {
      send(ResponseMessage(WorkspaceAgentErrorResponse(cmd.commandId, error, code, details)))
      send(CommandCompletedMessage(cmd.commandId, 1, (System.currentTimeMillis() - startTime).millis))
    }

    workspace.prepareCommand(cmd.command, cmd.workingDirectory, cmd.timeout, cmd.environment) match {
      case Left(refused) =>
        logger.info(s"Refused command ${cmd.commandId} (${refused.code}): ${refused.error}")
        fail(refused.error, refused.code, refused.details)

      case Right(prepared) =>
        // a client that has flooded its cancellations takes no new commands until they age out: the bound holds
        // without ever forgetting a cancellation a waiting command needs
        if (cancelledEarly.full)
          fail("Too many cancellations of commands not yet started; try again shortly", "WORKSPACE_BUSY", None)
        else if (cancelledEarly.remove(cmd.commandId) || !connected())
          // cancelled, or its connection closed, before it started: it never starts (exit 143)
          send(CommandCompletedMessage(cmd.commandId, 143, (System.currentTimeMillis() - startTime).millis))
        else
          // the registry waits at the gate (bounded), starts the process and tracks it until it exits, whatever
          // happens from here: nothing below holds or drops the process itself
          registry.spawn(
            owner,
            cmd.commandId,
            prepared.builder,
            () => !cancelledEarly.remove(cmd.commandId) && connected()
          ) match {
            case Left(notStarted) if notStarted.code == ProcessRegistry.NotStarted =>
              // cancelled, or its connection closed, while it waited at the gate: it never started (exit 143)
              send(CommandCompletedMessage(cmd.commandId, 143, (System.currentTimeMillis() - startTime).millis))
            case Left(refused)  => fail(refused.error, refused.code, refused.details)
            case Right(running) =>
              // tracked: a cancellation from now on finds it. One that came while it waited or started is in
              // `cancelledEarly`, and a closed connection's handler has already run: either kills it here
              if (cancelledEarly.remove(cmd.commandId) || !connected()) registry.kill(running): Unit
              // a client that cannot be told it started gets no output and could not cancel it: it is killed, and
              // stays tracked until it has exited
              Try(send(CommandStartedMessage(cmd.commandId, cmd.command))) match {
                case Success(_) => stream(cmd, prepared, running, send, fail)
                case Failure(ex) =>
                  logger.warn(s"Could not announce command ${cmd.commandId}; stopping it: ${ex.getMessage}")
                  registry.kill(running): Unit
              }
          }
    }
  }

  /** The runner's process registry: the workspace's. */
  private[runner] def registry: ProcessRegistry = workspace.processes

  /**
   * Cancel one of this client's running commands. Cancellation is acknowledged as completion, exit code 143.
   *
   * @param commandId the command to cancel
   * @param owner     the client, whose processes alone it can cancel
   * @param send      delivers a protocol message to the client
   * @param cancelledEarly this client's cancellations of commands not yet registered, as given to [[execute]]
   */
  def cancel(
    commandId: String,
    owner: AnyRef,
    send: WebSocketMessage => Unit,
    cancelledEarly: Option[EarlyCancellations] = None,
    onOverflow: () => Unit = () => ()
  ): Unit =
    // looked up, or recorded as an early cancellation, under the registry's lock: a command waiting at the gate either
    // is registered and found here, or sees the cancellation as its spawn decides whether to start
    registry.atomically(
      registry.find(owner, commandId).toRight(cancelledEarly.forall(_.add(commandId)))
    ) match {
      case Right(running) =>
        // killed through the registry, which keeps it tracked until it has exited
        val gone = registry.kill(running) // the parent and every descendant found
        logger.info(s"Command $commandId cancelled by client")
        // acknowledged only once it is gone, its whole tree; one that survives the kill is reported by its exit watcher
        // as a failure
        if (gone && running.completionSent.compareAndSet(false, true)) {
          val durationMs = System.currentTimeMillis() - running.startTimeMs
          send(CommandCompletedMessage(commandId, 143, durationMs.millis))
        }

      case Left(kept) =>
        // it may still be waiting at the gate: remembered, so it never starts. Always kept: a cancellation is never
        // dropped, and each is forgotten only once no command could still be waiting for it (EarlyCancellations)
        // a client past the bound is disconnected, which stops all its commands, rather than any cancellation dropped
        if (!kept) onOverflow()
        logger.warn(s"Cancellation requested for unknown or non-running command id $commandId")
        send(ErrorMessage(s"No running command found for id $commandId", "UNKNOWN_COMMAND_ID", Some(commandId)))
    }

  private def stream(
    cmd: ExecuteCommandCommand,
    prepared: PreparedCommand,
    running: RunningCommand,
    send: WebSocketMessage => Unit,
    fail: (String, String, Option[String]) => Unit
  ): Unit = {
    val process           = running.process
    val startTime         = running.startTimeMs
    val stdoutDone        = Promise[Unit]()
    val stderrDone        = Promise[Unit]()
    val exitDone          = Promise[Unit]()
    val exitCodePromise   = Promise[Int]()
    val stdoutTruncated   = new AtomicBoolean(false)
    val stderrTruncated   = new AtomicBoolean(false)
    val commandTimedOut   = new AtomicBoolean(false)
    val survivedKill      = new AtomicBoolean(false)
    val stdoutAccumulator = new StringBuilder()
    val stderrAccumulator = new StringBuilder()
    // the client's bound on each stream, never more than the runner's own
    val limit = WebSocketCommandExecutor.outputLimit(cmd.maxOutputChars, maxOutputSize)

    def streamOutput(
      outputType: String,
      stream: java.io.InputStream,
      accumulator: StringBuilder,
      truncated: AtomicBoolean,
      done: Promise[Unit]
    ): Unit =
      Future {
        // decoded by a reader, so a character split across two reads is decoded whole, and kept by characters:
        // at most `limit` of them, whatever the output's lines, while the rest is read and discarded
        val reader    = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)
        val buffer    = new Array[Char](8192)
        var charsRead = 0
        var captured  = 0L

        try
          while ({
            charsRead = reader.read(buffer)
            charsRead != -1
          }) {
            send(StreamingOutputMessage(cmd.commandId, outputType, new String(buffer, 0, charsRead)))
            val take = math.min(math.max(limit - captured, 0L), charsRead.toLong).toInt
            accumulator.appendAll(buffer, 0, take)
            captured += take
            if (take < charsRead) truncated.set(true)
          }
        catch {
          case ex: Exception =>
            logger.error(s"Error reading $outputType for command ${cmd.commandId}: ${ex.getMessage}", ex)
        } finally {
          send(StreamingOutputMessage(cmd.commandId, outputType, "", isComplete = true))
          done.trySuccess(())
        }
      }

    streamOutput("stdout", process.getInputStream, stdoutAccumulator, stdoutTruncated, stdoutDone)
    streamOutput("stderr", process.getErrorStream, stderrAccumulator, stderrTruncated, stderrDone)

    Future {
      val exitCode =
        try {
          // The caller's timeout, else the sandbox's default, as on the direct path.
          val deadlineMs = startTime + prepared.timeout.toMillis
          var finished   = false
          var timedOut   = false
          while (!finished && !running.cancelled.get())
            if (System.currentTimeMillis() >= deadlineMs) {
              timedOut = true
              finished = true
            } else {
              finished = process.waitFor(500, TimeUnit.MILLISECONDS)
            }
          if (running.cancelled.get()) {
            // a cancellation is an outcome only once the process is gone, as a timeout is
            if (!registry.kill(running)) survivedKill.set(true)
            143
          } else if (timedOut) {
            commandTimedOut.set(true)
            // a timeout is an outcome only once the process is gone
            if (!registry.kill(running, asCancellation = false)) survivedKill.set(true)
            -1
          } else {
            process.exitValue()
          }
        } catch {
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
            if (!registry.kill(running, asCancellation = false)) survivedKill.set(true)
            -1
          case ex: Exception =>
            logger.error(s"Error waiting for process ${cmd.commandId}: ${ex.getMessage}", ex)
            if (!registry.kill(running, asCancellation = false)) survivedKill.set(true)
            -1
        } finally
          // the registry, not this watcher, drops the process: when it has exited
          exitDone.trySuccess(())
      // complete only once the registry has released it: what it left running (a backgrounded process) is killed and
      // waited for first, so no later command or file change runs beside it. Not released in time is a survivor.
      if (
        !survivedKill.get() && Try(
          running.released.get(WebSocketCommandExecutor.ReleaseWait.toMillis, TimeUnit.MILLISECONDS)
        ).isFailure
      )
        survivedKill.set(true)
      // timed out and still alive after the kill: it may yet write, so its outcome is unknown - reported now, as a
      // failure, without waiting for output streams it may hold open
      if (survivedKill.get() && running.completionSent.compareAndSet(false, true)) {
        send(
          ResponseMessage(
            WorkspaceAgentErrorResponse(
              cmd.commandId,
              "Command, or a process it started, did not terminate when killed; it may still be running",
              "EXECUTION_FAILED",
              None
            )
          )
        )
        send(CommandCompletedMessage(cmd.commandId, -1, (System.currentTimeMillis() - startTime).millis))
      }
      exitCodePromise.trySuccess(exitCode)
    }

    stdoutDone.future
      .flatMap(_ => stderrDone.future)
      .flatMap(_ => exitDone.future)
      .flatMap(_ => exitCodePromise.future)
      .onComplete {
        case Success(_) if survivedKill.get() => () // reported when the kill failed
        case Success(rawExitCode) =>
          val durationMs = System.currentTimeMillis() - startTime
          val effectiveExitCode =
            if (running.cancelled.get()) 143
            else if (commandTimedOut.get()) -1
            else rawExitCode
          val marker =
            if (commandTimedOut.get()) s"[timed out after ${prepared.timeout.toSeconds}s and was killed]\n" else ""
          val fullStderr       = marker + stderrAccumulator.result()
          val stderrWithMarker = fullStderr.take(math.min(limit, Int.MaxValue.toLong).toInt)
          val isOutputTruncated =
            stdoutTruncated.get() || stderrTruncated.get() || stderrWithMarker.length < fullStderr.length

          send(
            ResponseMessage(
              ExecuteCommandResponse(
                commandId = cmd.commandId,
                stdout = stdoutAccumulator.result(),
                // a command its timeout killed says so, as the direct path's TIMEOUT does: exit -1 alone is ambiguous.
                // First, so a bound that cuts stderr's end keeps it
                // Within the stream's bound like any output: a marker that does not fit is cut, and says so.
                stderr = stderrWithMarker,
                exitCode = effectiveExitCode,
                isOutputTruncated = isOutputTruncated,
                duration = durationMs.millis
              )
            )
          )
          if (running.completionSent.compareAndSet(false, true)) {
            send(CommandCompletedMessage(cmd.commandId, effectiveExitCode, durationMs.millis))
          }
        case Failure(ex) =>
          fail(
            Option(ex.getMessage).getOrElse("Command execution failed"),
            "EXECUTION_FAILED",
            Some(ex.getStackTrace.mkString("\n"))
          )
      }
  }
}

/**
 * One client's cancellations of commands not yet registered (waiting at the gate, or not yet taken up). Each is kept
 * for `window`, longer than any command can wait before it starts or is refused (the gate's bounded wait, plus its
 * preparation), and then forgotten: no cancellation a waiting command needs is ever dropped. Past `capacity` live
 * entries the client is [[full]], and takes no new command until they age out, so the set is bounded either way.
 */
final private[runner] class EarlyCancellations(
  window: FiniteDuration = EarlyCancellations.Window,
  capacity: Int = EarlyCancellations.Capacity,
  clock: () => Long = () => System.nanoTime()
) {
  private val at = new ConcurrentHashMap[String, java.lang.Long]()

  private def purge(): Unit = {
    val cutoff = clock() - window.toNanos
    at.entrySet().removeIf(e => e.getValue.longValue < cutoff): Unit
  }

  /** Records `id`; `false`, recording nothing, when the client already has `capacity` live entries. */
  def add(id: String): Boolean = {
    purge()
    if (at.size >= capacity) false else { at.put(id, clock()): Unit; true }
  }

  /** Whether `id` was cancelled, forgetting it. */
  def remove(id: String): Boolean = at.remove(id) != null

  def full: Boolean = { purge(); at.size >= capacity }
}

private[runner] object EarlyCancellations {

  /** Longer than any command waits before it starts or is refused. */
  val Window: FiniteDuration = WorkspaceSandboxConfig.CommandGateWait + 2.minutes

  /** The most live cancellations of commands not yet started one client may have before its new commands wait. */
  val Capacity: Int = 1024
}

private[runner] object WebSocketCommandExecutor {

  /** How long a command's end waits for the registry to release it: kill and wait for what it left running. */
  val ReleaseWait: FiniteDuration = ProcessRegistry.KillWait * 2

  /** The most characters of each stream a command keeps: what it asked for, within `0..runnerMax`. */
  def outputLimit(requested: Option[Long], runnerMax: Long): Long =
    requested.fold(runnerMax)(r => math.min(math.max(r, 0L), runnerMax))
}
