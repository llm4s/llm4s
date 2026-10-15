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
  completionSent: AtomicBoolean,
  // the descendants a kill found (they may outlive the parent): the entry is tracked, and holds its permit, until
  // every one of them has exited too
  tree: java.util.Set[ProcessHandle] = java.util.concurrent.ConcurrentHashMap.newKeySet[ProcessHandle](),
  // completed when the registry releases it: the parent and everything it left running have exited
  released: java.util.concurrent.CompletableFuture[Unit] = new java.util.concurrent.CompletableFuture[Unit]()
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

  // owners whose connection has closed (killOwner), under the registry's lock: nothing more is started for them
  private val closedOwners = java.util.Collections.newSetFromMap(new java.util.WeakHashMap[AnyRef, java.lang.Boolean]())

  /** Thrown inside the lock when `proceed` says no: the command was cancelled, or its client left, while it waited. */
  private object NotStarted extends RuntimeException("Cancelled before it started", null, false, false)

  /**
   * `body` under the registry's lock, the one `spawn` asks `proceed` and starts under: a cancellation recorded here,
   * for a command not yet registered, is seen by that command's `spawn`, or the command is registered and found.
   */
  def atomically[A](body: => A): A = synchronized(body)

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
    else {
      // asked, started and registered in one step under the registry's lock: killAll and killOwner never miss a
      // process between the two, and a command cancelled (or whose client left) while it waited never starts
      val started = synchronized(ProcessRegistry.ledger.synchronized {
        // one process per id and owner: a second would hide the first from cancellation
        (if (closed) scala.util.Failure(new IllegalStateException("The runner is shutting down"))
         else if (closedOwners.contains(owner) || !proceed()) scala.util.Failure(NotStarted)
         else if (running.containsKey((owner, commandId)))
           scala.util.Failure(new IllegalStateException(s"Command $commandId is already running"))
         else Try(ProcessRegistry.started(start(builder), ProcessRegistry.commands))).map { process =>
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
      })
      started.toEither match {
        case Left(NotStarted) =>
          gate.release()
          Left(new WorkspaceAgentException("Cancelled before it started", ProcessRegistry.NotStarted, None))
        case Left(refused: WorkspaceAgentException) =>
          // a check the start step made (where it would run) refused it: nothing started, its own code kept
          gate.release()
          Left(refused)
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
            // what it left running is stopped, and waited for, before its permit is given back: a file change never
            // runs beside a process of this command. One that survives its kill keeps the entry tracked, holding the
            // permit, until it has really exited, as a surviving parent does
            val leftovers =
              (entry.tree.asScala.toList ++ sweepOrphans(Try(entry.process.pid()).toOption)).distinct.filter(_.isAlive)
            leftovers.foreach(h => Try(h.destroyForcibly()): Unit)
            def done(): Unit = {
              running.remove((owner, commandId), entry)
              gate.release()
              entry.released.complete(()): Unit
            }
            if (leftovers.isEmpty) done()
            else {
              logger.warn(
                s"Command $commandId left ${leftovers.size} process(es) running; it holds the gate until they exit"
              )
              java.util.concurrent.CompletableFuture
                .allOf(leftovers.map(_.onExit()): _*)
                .whenComplete((_, _) => done()): Unit
            }
          }: Unit
          Right(entry)
      }
    }

  /**
   * Kills what a command that exited on its own left behind. A descendant whose parent has exited is re-parented to
   * the init process, which in the runner's container is the runner itself (PID 1): every child of the runner that is
   * no tracked command is such an orphan, and is killed with its tree. Outside a container (the runner not PID 1) an
   * orphan goes to the host's init and cannot be found; there the container is the boundary. A file operation's own
   * helper (`getfacl`) never runs then: it needs the whole gate, and the exiting command still holds its permit.
   *
   * An orphan cannot be told apart from the re-parented descendant of a command still running, so the sweep is made
   * only by the last command to exit (`self` is the exiting one): while another runs, nothing is swept, and no file
   * operation can run either (that command holds a permit), so an orphan never runs beside a file change; the last
   * command's exit sweeps every orphan before its permit is given back.
   */
  private def sweepOrphans(self: Option[Long]): List[ProcessHandle] =
    if (ProcessHandle.current().pid() != 1L) Nil
    else
      ProcessRegistry.ledger.synchronized {
        val othersRunning = ProcessRegistry.commands.asScala.exists { pid =>
          !self.contains(pid.longValue) && ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)
        }
        if (othersRunning) Nil
        else sweepAll()
      }

  private def sweepAll(): List[ProcessHandle] =
    ProcessRegistry.ledger.synchronized {
      // every process the runner starts itself is in the ledger from its start (a command, a helper), so a child that
      // is in neither is a re-parented orphan, never a process being started or a helper at work
      val own = ProcessRegistry.commands.asScala ++ ProcessRegistry.helpers.asScala
      ProcessHandle.current().children().toList.asScala.filterNot(h => own.contains(h.pid())).toList.flatMap { orphan =>
        val tree = Try(orphan.descendants().toList.asScala.toList).getOrElse(Nil)
        tree.foreach(d => Try(d.destroyForcibly()): Unit)
        Try(orphan.destroyForcibly()): Unit
        orphan :: tree
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
    // its whole tree, children first, collected before the parent dies and they are re-parented out of reach: a
    // command's build tool or shell never leaves a process changing the workspace after it is killed
    Try(entry.process.descendants().toList.asScala).getOrElse(Nil).foreach(entry.tree.add(_): Unit)
    // every descendant ever found, this kill's and an earlier one's: a repeated kill still waits for a survivor
    val tree = entry.tree.asScala.toList.filter(_.isAlive)
    tree.foreach(d => Try(d.destroyForcibly()): Unit)
    Try(entry.process.destroyForcibly()).failed.foreach { e =>
      logger.error(s"Error killing command ${entry.commandId}: ${e.getMessage}", e)
    }
    // gone only once the registry has released it: the parent, every descendant found and every orphan the exit swept
    // have exited (`released`), within the one wait. Every caller - a cancellation's acknowledgement, a timeout, a
    // disconnect - judges the outcome by this, never by the part of the tree it could see
    val gone = Try(entry.released.get(ProcessRegistry.KillWait.toMillis, TimeUnit.MILLISECONDS)).isSuccess
    if (!gone) logger.warn(s"Command ${entry.commandId} did not terminate when killed; it stays tracked until it exits")
    gone
  }

  /** Kills every process of `owner` (its connection closed); each stays tracked until it exits. */
  def killOwner(owner: AnyRef): Unit =
    synchronized {
      // closed in the same step: a command of this owner still waiting to start is refused, never started after
      closedOwners.add(owner)
      running.values().asScala.filter(_.owner eq owner).toList
    }.foreach(kill(_): Unit)

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

  /**
   * The ledger of the runner's own children, JVM-wide: every command's process and every helper (`git config`,
   * `getfacl`, `setfacl`) is entered under this lock in the step that starts it, and leaves when it exits. The orphan
   * sweep reads it under the same lock, so it never takes a process being started, or a helper, for an orphan.
   */
  private[runner] val ledger                                  = new Object
  private[runner] val commands: java.util.Set[java.lang.Long] = ConcurrentHashMap.newKeySet[java.lang.Long]()
  private[runner] val helpers: java.util.Set[java.lang.Long]  = ConcurrentHashMap.newKeySet[java.lang.Long]()

  /** `process`, entered in `into` until it exits. Called within the step that started it. */
  private[runner] def started(process: Process, into: java.util.Set[java.lang.Long]): Process = {
    Try(process.pid()).foreach { pid =>
      into.add(pid)
      process.onExit().whenComplete((_, _) => into.remove(pid): Unit)
    }
    process
  }

  /** Starts a helper of the runner's own (not a command), entered in the ledger from its start. */
  def startHelper(builder: java.lang.ProcessBuilder): Process = ledger.synchronized(started(builder.start(), helpers))

  /** The gate's permits: one per running command, all of them for a file operation. */
  val GatePermits: Int = 1 << 20

  /** The code of a spawn whose `proceed` said no: nothing was started. */
  val NotStarted: String = "NOT_STARTED"

  /** How long a kill waits for the process to exit before it reports that it did not. */
  val KillWait: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)
}
