// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
package org.llm4s.runner

import org.llm4s.shared.WorkspaceAgentException
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ ConcurrentHashMap, Semaphore, TimeUnit }
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Try

/** A process the runner started, as the [[ProcessRegistry]] tracks it. */
final private[runner] case class RunningCommand(
  owner: AnyRef,
  commandId: String,
  process: Process,
  cancelled: AtomicBoolean,
  startTimeMs: Long,
  completionSent: AtomicBoolean
)

/**
 * '''Every process the runner starts''' (design §4.33), and the command gate that keeps them apart from file
 * operations. The registry is the only holder of process references:
 *
 *  - '''Tracked from spawn to exit.''' [[spawn]] starts the process and registers it in one step, under the registry's
 *    lock, and only the process's own `onExit` removes it and returns its gate permit. Nothing else drops a process:
 *    a failed start announcement, a cancellation, a disconnect or shutdown kills it, and it stays tracked - findable,
 *    cancellable, holding its permit - until it has really exited.
 *  - '''The gate.''' A running command holds one permit; a file operation takes them all ([[exclusive]]), so it never
 *    runs beside a command the runner started. Every wait is bounded by `gateWait`, after which the request is refused
 *    (`WORKSPACE_BUSY`) having done nothing.
 *  - '''Ownership.''' Each process belongs to an owner (a WebSocket connection, or the direct path), so a cancellation
 *    or a disconnect acts on that owner's processes only; [[killAll]] is shutdown's.
 *
 * @param gateWait how long a command or a file operation waits at the gate before it is refused
 * @param start    starts a process; the platform's, bar in tests
 */
final private[runner] class ProcessRegistry(
  gateWait: FiniteDuration,
  start: java.lang.ProcessBuilder => Process = _.start()
) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val gate    = new Semaphore(ProcessRegistry.GatePermits, true)
  private val running = new ConcurrentHashMap[(AnyRef, String), RunningCommand]()

  // set by killAll, under the registry's lock: from then on nothing is started, so no process outlives the runner
  @volatile private var closed = false

  private def busy(what: String) =
    new WorkspaceAgentException(
      s"$what is in progress in the workspace; nothing was started (waited $gateWait)",
      "WORKSPACE_BUSY",
      None
    )

  /**
   * Waits for a command permit (bounded), starts the process and registers it, all before returning: from here the
   * process is tracked until its `onExit`, whatever the caller does next. Refused (`WORKSPACE_BUSY`) if a file
   * operation holds the gate past its wait, or `EXECUTION_FAILED` if the process cannot start; neither leaves
   * anything behind.
   */
  def spawn(
    owner: AnyRef,
    commandId: String,
    builder: java.lang.ProcessBuilder,
    proceed: () => Boolean = () => true
  ): Either[WorkspaceAgentException, RunningCommand] =
    if (!gate.tryAcquire(gateWait.toMillis, TimeUnit.MILLISECONDS)) Left(busy("A file change"))
    // asked again once the gate is had: a command cancelled (or whose client left) while it waited never starts
    else if (!proceed()) {
      gate.release()
      Left(new WorkspaceAgentException("Cancelled before it started", ProcessRegistry.NotStarted, None))
    } else {
      // start and register under the registry's lock, so killAll never misses a process between the two
      val started = synchronized {
        // one process per id and owner: a second would hide the first from cancellation
        (if (closed) scala.util.Failure(new IllegalStateException("The runner is shutting down"))
         else if (running.containsKey((owner, commandId)))
           scala.util.Failure(new IllegalStateException(s"Command $commandId is already running"))
         else Try(start(builder))).map { process =>
          val entry = RunningCommand(
            owner,
            commandId,
            process,
            new AtomicBoolean(false),
            System.currentTimeMillis(),
            new AtomicBoolean(false)
          )
          running.put((owner, commandId), entry)
          entry
        }
      }
      started.toEither match {
        case Left(e) =>
          gate.release()
          Left(
            new WorkspaceAgentException(
              Option(e.getMessage).getOrElse("Failed to start process"),
              "EXECUTION_FAILED",
              None
            )
          )
        case Right(entry) =>
          // the only removal: when the process has really exited, it leaves the registry and gives back its permit
          entry.process.onExit().whenComplete { (_, _) =>
            running.remove((owner, commandId), entry)
            gate.release()
          }: Unit
          Right(entry)
      }
    }

  /** The owner's process `commandId`, while it runs. */
  def find(owner: AnyRef, commandId: String): Option[RunningCommand] = Option(running.get((owner, commandId)))

  /** Whether the owner's process `commandId` is still tracked. */
  def tracked(owner: AnyRef, commandId: String): Boolean = running.containsKey((owner, commandId))

  /**
   * Kills `entry`, marked cancelled, and waits briefly for it to exit; `true` if it has. It stays tracked either way
   * until its `onExit`: one that survives the kill is still found by a later cancellation, disconnect or shutdown.
   */
  def kill(entry: RunningCommand, asCancellation: Boolean = true): Boolean = {
    // a cancellation (by the client, a disconnect, shutdown) is reported as one; a timeout's kill is not
    if (asCancellation) entry.cancelled.set(true)
    Try(entry.process.destroyForcibly()).failed.foreach { e =>
      logger.error(s"Error killing command ${entry.commandId}: ${e.getMessage}", e)
    }
    val gone = Try(entry.process.waitFor(ProcessRegistry.KillWait.toMillis, TimeUnit.MILLISECONDS)).getOrElse(false)
    if (!gone) logger.warn(s"Command ${entry.commandId} did not terminate when killed; it stays tracked until it exits")
    gone
  }

  /** Kills every process of `owner` (its connection closed); each stays tracked until it exits. */
  def killOwner(owner: AnyRef): Unit =
    running.values().asScala.filter(_.owner eq owner).toList.foreach(kill(_): Unit)

  /**
   * Kills every process (shutdown); each stays tracked until it exits. The registry is closed in the same step, so a
   * command still waiting to start is refused, never started after the processes were collected.
   */
  def killAll(): Unit = synchronized { closed = true; running.values().asScala.toList }.foreach(kill(_): Unit)

  /** Every process still tracked. */
  def all: List[RunningCommand] = running.values().asScala.toList

  /**
   * `body` with the whole gate: no command runs beside it. Waits at most `remaining` nanoseconds, then refuses.
   */
  def exclusive[A](remainingNanos: Long)(body: => A): A = {
    if (!gate.tryAcquire(ProcessRegistry.GatePermits, remainingNanos, TimeUnit.NANOSECONDS)) throw busy("A command")
    try body
    finally gate.release(ProcessRegistry.GatePermits)
  }
}

private[runner] object ProcessRegistry {

  /** The gate's permits: one per running command, all of them for a file operation. */
  val GatePermits: Int = 1 << 20

  /** The code of a spawn whose `proceed` said no: nothing was started. */
  val NotStarted: String = "NOT_STARTED"

  /** How long a kill waits for the process to exit before it reports that it did not. */
  val KillWait: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)
}
