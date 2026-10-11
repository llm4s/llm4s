// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch, DisableSyntax.NoKeywordFinally
package org.llm4s.runner

import org.llm4s.shared._
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ ConcurrentHashMap, TimeUnit }
import scala.concurrent.duration.DurationLong
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
 * @param maxOutputSize the most of each stream kept for the final `ExecuteCommandResponse`; chunks stream regardless
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
   */
  def execute(
    cmd: ExecuteCommandCommand,
    processes: ConcurrentHashMap[String, RunningCommand],
    send: WebSocketMessage => Unit
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
        send(CommandStartedMessage(cmd.commandId, cmd.command))
        Try(prepared.builder.start()) match {
          case Failure(ex) =>
            fail(
              Option(ex.getMessage).getOrElse("Failed to start process"),
              "EXECUTION_FAILED",
              Some(ex.getStackTrace.mkString("\n"))
            )
          case Success(process) =>
            val running = RunningCommand(process, new AtomicBoolean(false), startTime, new AtomicBoolean(false))
            processes.put(cmd.commandId, running)
            stream(cmd, prepared, running, processes, send, fail)
        }
    }
  }

  /**
   * Cancel one of this client's running commands. Cancellation is acknowledged as completion, exit code 143.
   *
   * @param commandId the command to cancel
   * @param processes this client's running commands
   * @param send      delivers a protocol message to the client
   */
  def cancel(
    commandId: String,
    processes: Option[ConcurrentHashMap[String, RunningCommand]],
    send: WebSocketMessage => Unit
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
        if (running.completionSent.compareAndSet(false, true)) {
          val durationMs = System.currentTimeMillis() - running.startTimeMs
          send(CommandCompletedMessage(commandId, 143, durationMs.millis))
        }

      case None =>
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
            send(StreamingOutputMessage(cmd.commandId, outputType, chunk))

            if (captured < maxOutputSize) {
              val remaining = (maxOutputSize - captured).toInt
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
            143
          } else if (timedOut) {
            commandTimedOut.set(true)
            process.destroyForcibly()
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
          processes.remove(cmd.commandId)
          exitDone.trySuccess(())
        }
      exitCodePromise.trySuccess(exitCode)
    }

    stdoutDone.future
      .flatMap(_ => stderrDone.future)
      .flatMap(_ => exitDone.future)
      .flatMap(_ => exitCodePromise.future)
      .onComplete {
        case Success(rawExitCode) =>
          val durationMs = System.currentTimeMillis() - startTime
          val effectiveExitCode =
            if (running.cancelled.get()) 143
            else if (commandTimedOut.get()) -1
            else rawExitCode
          val isOutputTruncated = stdoutTruncated.get() || stderrTruncated.get()

          send(
            ResponseMessage(
              ExecuteCommandResponse(
                commandId = cmd.commandId,
                stdout = stdoutAccumulator.result(),
                stderr = stderrAccumulator.result(),
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
