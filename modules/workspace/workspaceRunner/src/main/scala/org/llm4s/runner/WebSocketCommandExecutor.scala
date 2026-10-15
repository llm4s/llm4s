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

/** A process started for a WebSocket `ExecuteCommandCommand`, tracked so the client can cancel it. */
final private[runner] case class RunningCommand(
  process: Process,
  cancelled: AtomicBoolean,
  startTimeMs: Long,
  completionSent: AtomicBoolean
)

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
   * @param processes this client's running commands, so [[cancel]] can find the process
   * @param send      delivers a protocol message to the client
   * @param connected whether the client's connection is still open: a command whose connection closed while it waited at
   *                  the gate never starts, as the close handler cancels only what it can find
   * @param cancelledEarly this client's cancellations of commands not yet registered (waiting at the gate), which
   *                  therefore never start; one set per connection, shared with [[cancel]]
   */
  def execute(
    cmd: ExecuteCommandCommand,
    processes: ConcurrentHashMap[String, RunningCommand],
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
        // a permit of the workspace's command gate, from before the process starts until it has exited, so no file
        // mutation runs beside it; taken before the command is announced, and refused (nothing started) if a file
        // change holds the gate past its bounded wait
        // a client that has flooded its cancellations takes no new commands until they age out: the bound holds
        // without ever forgetting a cancellation a waiting command needs
        (if (cancelledEarly.full)
           Failure(
             new WorkspaceAgentException(
               "Too many cancellations of commands not yet started; try again shortly",
               "WORKSPACE_BUSY",
               None
             )
           )
         else Try(workspace.acquireCommand())) match {
          case Failure(busy: WorkspaceAgentException) => fail(busy.error, busy.code, busy.details)
          case Failure(other) => fail(Option(other.getMessage).getOrElse("Gate failed"), "EXECUTION_FAILED", None)
          case Success(_) if cancelledEarly.remove(cmd.commandId) || !connected() =>
            // cancelled while it waited at the gate: it never starts, and is acknowledged as a cancellation
            workspace.releaseCommand()
            send(CommandCompletedMessage(cmd.commandId, 143, (System.currentTimeMillis() - startTime).millis))
          case Success(_) =>
            Try(prepared.builder.start()) match {
              case Failure(ex) =>
                workspace.releaseCommand()
                fail(
                  Option(ex.getMessage).getOrElse("Failed to start process"),
                  "EXECUTION_FAILED",
                  Some(ex.getStackTrace.mkString("\n"))
                )
              case Success(process) =>
                // the permit goes back when the process exits, however this path ends from here: registered first, so a
                // failure to announce or stream it (a dropped socket) cannot keep the gate held
                workspace.releaseOnExit(Some(process))
                // registered before it is announced, so a cancellation from then on finds it; the timeout and the
                // duration count from the start of the process, not from before the wait for the gate
                val running =
                  RunningCommand(
                    process,
                    new AtomicBoolean(false),
                    System.currentTimeMillis(),
                    new AtomicBoolean(false)
                  )
                processes.put(cmd.commandId, running)
                // registered: a cancellation from now on finds it. One that came while it started, before it was
                // registered, is in `cancelledEarly`, and a closed connection's handler has already run: either stops it here
                if (cancelledEarly.remove(cmd.commandId) || !connected()) {
                  running.cancelled.set(true)
                  process.destroyForcibly(): Unit
                }
                // a client that cannot be told it started gets no output and could not cancel it: it is stopped
                Try(send(CommandStartedMessage(cmd.commandId, cmd.command))) match {
                  case Success(_) => stream(cmd, prepared, running, processes, send, fail)
                  case Failure(ex) =>
                    logger.warn(s"Could not announce command ${cmd.commandId}; stopping it: ${ex.getMessage}")
                    running.cancelled.set(true)
                    processes.remove(cmd.commandId)
                    process.destroyForcibly(): Unit
                }
            }
        }
    }
  }

  /**
   * Cancel one of this client's running commands. Cancellation is acknowledged as completion, exit code 143.
   *
   * @param commandId the command to cancel
   * @param processes this client's running commands
   * @param send      delivers a protocol message to the client
   * @param cancelledEarly this client's cancellations of commands not yet registered, as given to [[execute]]
   */
  def cancel(
    commandId: String,
    processes: Option[ConcurrentHashMap[String, RunningCommand]],
    send: WebSocketMessage => Unit,
    cancelledEarly: Option[EarlyCancellations] = None,
    onOverflow: () => Unit = () => ()
  ): Unit =
    processes.flatMap(running => Option(running.get(commandId))) match {
      case Some(running) =>
        running.cancelled.set(true)
        val terminationAttempt = Try {
          val destroyedProcess = running.process.destroyForcibly()
          // Wait briefly for the process to actually terminate
          if (!destroyedProcess.waitFor(5, TimeUnit.SECONDS)) {
            logger.warn(s"Process for command $commandId did not terminate within timeout after cancellation")
          }
        }
        terminationAttempt.failed.foreach { ex =>
          logger.error(s"Error destroying process for command $commandId: ${ex.getMessage}", ex)
        }
        logger.info(s"Command $commandId cancelled by client")
        // acknowledged only once it is gone; one that survives the kill is reported by its exit watcher as a failure
        if (!running.process.isAlive && running.completionSent.compareAndSet(false, true)) {
          val durationMs = System.currentTimeMillis() - running.startTimeMs
          send(CommandCompletedMessage(commandId, 143, durationMs.millis))
        }

      case None =>
        // it may still be waiting at the gate: remember it, so it never starts. Always kept: a cancellation is never
        // dropped, and each is forgotten only once no command could still be waiting for it (EarlyCancellations)
        // a client past the bound is disconnected, which stops all its commands, rather than any cancellation dropped
        cancelledEarly.foreach(early => if (!early.add(commandId)) onOverflow())
        logger.warn(s"Cancellation requested for unknown or non-running command id $commandId")
        send(ErrorMessage(s"No running command found for id $commandId", "UNKNOWN_COMMAND_ID", Some(commandId)))
    }

  private def stream(
    cmd: ExecuteCommandCommand,
    prepared: PreparedCommand,
    running: RunningCommand,
    processes: ConcurrentHashMap[String, RunningCommand],
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
            process.destroyForcibly()
            // a cancellation is an outcome only once the process is gone, as a timeout is
            if (!process.waitFor(5, TimeUnit.SECONDS)) survivedKill.set(true)
            143
          } else if (timedOut) {
            commandTimedOut.set(true)
            process.destroyForcibly()
            // a timeout is an outcome only once the process is gone
            if (!process.waitFor(5, TimeUnit.SECONDS)) survivedKill.set(true)
            -1
          } else {
            process.exitValue()
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
          // a process that survived its kill stays registered, so a cancellation still finds it, until it exits
          if (survivedKill.get())
            process.onExit().whenComplete((_, _) => processes.remove(cmd.commandId): Unit): Unit
          else processes.remove(cmd.commandId): Unit
          exitDone.trySuccess(())
        }
      // timed out and still alive after the kill: it may yet write, so its outcome is unknown - reported now, as a
      // failure, without waiting for output streams it may hold open
      if (survivedKill.get() && running.completionSent.compareAndSet(false, true)) {
        send(
          ResponseMessage(
            WorkspaceAgentErrorResponse(
              cmd.commandId,
              "Command did not terminate when killed (timed out or cancelled); it may still be running",
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

  /** The most characters of each stream a command keeps: what it asked for, within `0..runnerMax`. */
  def outputLimit(requested: Option[Long], runnerMax: Long): Long =
    requested.fold(runnerMax)(r => math.min(math.max(r, 0L), runnerMax))
}
