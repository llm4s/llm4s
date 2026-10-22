package org.llm4s.agent.service

import org.llm4s.agent.{ AgentId, AgentRun }
import org.llm4s.agent.graph.*
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, Semaphore, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Where an [[AgentWorker]] is in its life: built and not started, taking and running entries, draining on shutdown, or
 * stopped.
 */
@Experimental
enum WorkerState:
  case NotStarted, Running, Draining, Stopped

/**
 * A worker's readiness ([[AgentWorker.health]], for a service's probes): `ready` when it is running, its loop is alive
 * and taking, and its store answers; otherwise `problems` says why, with no input or other content in them. `running`
 * is how many runs it has now.
 */
@Experimental
final case class WorkerHealth(ready: Boolean, state: WorkerState, running: Int, problems: Vector[String])

/**
 * How a drain ended ([[AgentWorker.shutdown]]): the entries whose runs ended by themselves within the grace period
 * (`finished`), those the worker cancelled that then exited (`cancelled` - each given back to the queue, or closed from
 * how it ended, so another worker recovers it at once), and those that had not exited by the second bound
 * (`abandoned` - their claims and leases are kept, and expire after their ttl once the process exits).
 */
@Experimental
final case class DrainReport(finished: Vector[EntryId], cancelled: Vector[EntryId], abandoned: Vector[EntryId])

/**
 * One pass of the worker's retention sweep ([[AgentWorker.sweepOnce]]): how many threads it visited and tombstones it
 * reclaimed (`Checkpointer.sweep`), and how many closed queue entries past their ticket lifetime it reclaimed
 * (`RunQueue.reclaim`). `skipped` when another instance was sweeping the store, so this one did nothing.
 */
@Experimental
final case class SweepReport(examined: Int, tombstones: Int, entries: Int, skipped: Boolean)

/**
 * Runs queued entries (Stage 5 W2, design 4.56): it takes entries from a store's [[RunQueue]] and runs them on the
 * agents it was built with, whose runtimes are over the same store, at most `settings.concurrency` at once. Built with
 * [[AgentWorker.apply]], started with [[start]], stopped with [[shutdown]].
 *
 * For each entry it takes it:
 *
 *  1. holds the entry's lease, renewing it every `lease.renewEvery` beside the run's claim, which the run's runtime
 *     renews; a renewal the queue refuses (another worker took the entry over, or it closed) cancels the run, which
 *     then writes nothing more, rather than run on beside the entry's next attempt;
 *  1. defers to a live claim on the thread - a run there, queued or direct, on any machine - by giving the entry back
 *     (`RunQueue.release`) without recording an attempt, so deferring never spends `maxAttempts`;
 *  1. leaves an entry whose thread its agent's graph cannot take on - another graph version's run in flight, during a
 *     rolling upgrade - for a worker that can, passing over it for a while, and closes it `Failed(Unrunnable)` once it
 *     has waited `settings.unrunnableAfter` by the store's clock;
 *  1. records the attempt with a new `RunId.random()` before touching the thread (rule 4), then acts on
 *     [[AttemptPlan]]: starts the input, recovers the thread, or closes the entry from the thread's state;
 *  1. closes the entry from the thread's state once its run has ended at rest, or - for a run that failed - leaves it
 *     to come back when its lease expires, for an attempt that recovers it, until its attempts run out;
 *  1. on `AttemptStart.Exhausted`, settles the thread first if its input was admitted - each tool call left without a
 *     result gets a synthetic one and a `Failed` checkpoint is committed under the thread's claim - and closes the entry
 *     `Failed(AttemptsExhausted)`.
 *
 * A crashed worker's entries come back when their leases expire, by the store's clock, and the worker that takes one
 * recovers its thread, once the crashed run's claim - on the same clock - has expired too.
 *
 * With `settings.sweepEvery` set it also runs the store-wide retention sweep (`Checkpointer.sweep`, with a guard that
 * keeps every open entry's runs and its thread's tombstone) and reclaims closed entries past their ticket lifetime, one
 * instance of the fleet at a time: the sweep holds a store claim on a reserved thread id while it runs.
 */
@Experimental
final class AgentWorker private (
  val queue: RunQueue,
  val checkpointer: Checkpointer,
  agents: Map[AgentId, QueuedAgent],
  val settings: WorkerSettings,
  val queueSettings: RunQueueSettings
):
  import AgentWorker.*

  /** The lease holder this worker takes entries as: its label, if any, and a random suffix. */
  val holder: String =
    s"${settings.label.getOrElse("worker")}-${java.util.UUID.randomUUID().toString.take(8)}"

  private val lease = queueSettings.lease

  private val state    = new AtomicReference[WorkerState](WorkerState.NotStarted)
  private val stopping = new CountDownLatch(1)
  private val permits  = new Semaphore(settings.concurrency)
  private val slots    = new ConcurrentHashMap[EntryId, Slot]()
  private val skipped  = new ConcurrentHashMap[EntryId, java.lang.Long]()
  private val threads  = new AtomicReference[Vector[Thread]](Vector.empty)

  @volatile private var loopBeat: Long              = System.nanoTime()
  @volatile private var takeProblem: Option[String] = None

  /** A test hook run by an entry's slot just before it records its attempt; a no-op by default. */
  @volatile private[service] var beforeAttempt: QueueEntry => Unit = _ => ()

  // ---- life ----

  /** Starts taking entries, and the retention sweep's schedule. Once only: a second call changes nothing. */
  def start(): Unit =
    if state.compareAndSet(WorkerState.NotStarted, WorkerState.Running) then
      loopBeat = System.nanoTime()
      val loop = Thread.ofVirtual().name(s"llm4s-worker-$holder").start(() => takeLoop())
      val sweep = settings.sweepEvery.map(every =>
        Thread.ofVirtual().name(s"llm4s-worker-sweep-$holder").start(() => sweepLoop(every))
      )
      threads.set(Vector(loop) ++ sweep)
      logger.info(s"Worker $holder started: agents ${agents.keys.map(_.value).toVector.sorted.mkString(", ")}")

  /**
   * Drains and stops the worker: it stops taking entries, lets its running runs reach a suspension or their end within
   * `settings.drainGrace`, then cancels the rest and waits up to `settings.exitTimeout` for them to exit. A cancelled
   * run's lease is given back (or its entry closed from how it ended) only once the run has exited, and its claim
   * is released by its runtime as it exits, so another worker recovers it at once without overlapping its side
   * effects. A run that has not exited by then keeps its claim and lease: renewing the lease stops here, the claim's
   * renewal stops when the process exits, and both expire after their ttl, as after a crash. Returns at once, with
   * an empty report, on a worker never started or already stopped.
   */
  def shutdown(): DrainReport =
    if state.compareAndSet(WorkerState.NotStarted, WorkerState.Stopped) then
      DrainReport(Vector.empty, Vector.empty, Vector.empty)
    else if !state.compareAndSet(WorkerState.Running, WorkerState.Draining) then
      DrainReport(Vector.empty, Vector.empty, Vector.empty)
    else
      stopping.countDown()
      // the loop's take in flight lands before the slots are read; the sweep's page is bounded by the store
      threads.get.foreach(t => Try(t.join(java.time.Duration.ofNanos((settings.pollInterval + lease.ttl).toNanos))))
      val graceEnd         = System.nanoTime() + settings.drainGrace.toNanos
      val draining         = slots.values.asScala.toVector
      val (finished, late) = draining.partition(s => awaitLatch(s.ended, graceEnd))
      late.foreach(_.cancelForDrain())
      val exitEnd             = System.nanoTime() + settings.exitTimeout.toNanos
      val (exited, abandoned) = late.partition(s => awaitLatch(s.ended, exitEnd))
      // renewal stops: the leases of runs that did not exit expire after their ttl, as after a crash
      abandoned.foreach { s =>
        s.renewing.set(false)
        logger.warn(
          s"Worker $holder: the run of entry ${s.entryId.value} did not exit within ${settings.exitTimeout} of " +
            "being cancelled; its claim and lease are left to expire"
        )
      }
      state.set(WorkerState.Stopped)
      DrainReport(finished.map(_.entryId), exited.map(_.entryId), abandoned.map(_.entryId))

  /** Where the worker is in its life. */
  def currentState: WorkerState = state.get

  /**
   * The worker's readiness, for a service's probes: it is running, its loop has beaten within a bound and its last
   * take succeeded, and the store answers a read of the checkpoints and of the queue, each within the store's bound.
   */
  def health(): WorkerHealth =
    val now = state.get
    val life = now match
      case WorkerState.Running    => Vector.empty
      case WorkerState.NotStarted => Vector("the worker has not started")
      case WorkerState.Draining   => Vector("the worker is draining")
      case WorkerState.Stopped    => Vector("the worker has stopped")
    val loopBound = (settings.pollInterval * 2 + lease.ttl).toNanos
    val loop =
      if now != WorkerState.Running then Vector.empty
      else if !threads.get.headOption.exists(_.isAlive) then Vector("the worker's loop is not running")
      else if System.nanoTime() - loopBeat > loopBound then Vector("the worker's loop has stalled")
      else takeProblem.map(p => s"the worker's last take failed: $p").toVector
    val store    = checkpointer.latest(HealthProbe).left.map(e => s"the checkpoint store failed: ${e.message}")
    val held     = queue.openEntries(None, HealthProbe).left.map(e => s"the run queue failed: ${e.message}")
    val problems = life ++ loop ++ store.left.toOption ++ held.left.toOption
    WorkerHealth(problems.isEmpty, now, slots.size, problems)

  // ---- taking ----

  private def stopRequested: Boolean = stopping.getCount == 0

  private def takeLoop(): Unit =
    while !stopRequested do
      loopBeat = System.nanoTime()
      if Try(permits.tryAcquire(settings.pollInterval.toNanos, TimeUnit.NANOSECONDS)).getOrElse(false) then
        if stopRequested then permits.release()
        else
          queue.take(TakeRequest(holder, agents.keySet, lease.ttl, passedOver())) match
            case Right(Some(leased)) =>
              takeProblem = None
              launch(leased)
            case Right(None) =>
              takeProblem = None
              permits.release()
              pause(settings.pollInterval)
            case Left(error) =>
              takeProblem = Some(error.message)
              logger.warn(s"Worker $holder could not take an entry: ${error.message}")
              permits.release()
              pause(settings.pollInterval)

  /** Waits `d`, or until shutdown begins. */
  private def pause(d: FiniteDuration): Unit = Try(stopping.await(d.toNanos, TimeUnit.NANOSECONDS))

  /** The entries a take passes over now: those whose pass-over time has not run out, at most `TakeRequest.MaxSkip`. */
  private def passedOver(): Set[EntryId] =
    val now = System.nanoTime()
    skipped.entrySet.asScala.toVector.foreach(e => if e.getValue.longValue <= now then skipped.remove(e.getKey))
    skipped.keySet.asScala.take(TakeRequest.MaxSkip).toSet

  private def passOver(id: EntryId, d: FiniteDuration): Unit =
    skipped.put(id, java.lang.Long.valueOf(System.nanoTime() + d.toNanos))

  private def launch(leased: LeasedEntry): Unit =
    val slot = new Slot(leased, System.nanoTime())
    slots.put(slot.entryId, slot)
    Thread.ofVirtual().name(s"llm4s-worker-lease-${leased.entry.id.value}").start(() => renewLoop(slot))
    Thread.ofVirtual().name(s"llm4s-worker-run-${leased.entry.id.value}").start { () =>
      val done = Try(act(slot, decide(slot)))
      done.failed.foreach(thrown =>
        logger.error(s"Worker $holder: entry ${slot.entryId.value} failed in the worker: $thrown", thrown)
      )
      slot.renewing.set(false)
      slots.remove(slot.entryId)
      slot.ended.countDown()
      permits.release()
    }

  // ---- the lease ----

  /**
   * Renews `slot`'s lease every `lease.renewEvery` until its run ends: a renewal the queue refuses - the lease is not
   * current, or the entry closed - or none landing for a whole `lease.ttl` cancels the run, which stops at once.
   */
  private def renewLoop(slot: Slot): Unit =
    @tailrec def beat(lastSent: Long): Unit =
      val ended = Try(slot.ended.await(lease.renewEvery.toNanos, TimeUnit.NANOSECONDS)).getOrElse(true)
      if !ended && slot.renewing.get then
        val sent = System.nanoTime()
        queue.renew(slot.lease.get, lease.ttl) match
          case Right(renewed) =>
            slot.lease.set(renewed)
            beat(sent)
          case Left(error @ (_: QueueError.LeaseLost | _: QueueError.EntryClosed)) =>
            slot.lost(s"its lease renewal was refused: ${error.message}")
          case Left(error) =>
            if System.nanoTime() - lastSent >= lease.ttl.toNanos then
              slot.lost(s"no lease renewal landed within ${lease.ttl}: ${error.message}")
            else
              logger.warn(s"Worker $holder: renewing the lease of entry ${slot.entryId.value} failed: ${error.message}")
              beat(lastSent)
    beat(slot.takenAt)

  // ---- one entry ----

  /** What a slot does with its entry once it has decided. */
  private enum Next:
    /** Close the entry with this outcome. */
    case Close(outcome: EntryOutcome)

    /** Give the entry back at once, recording nothing more. */
    case Release

    /** Stop renewing: the entry comes back when its lease expires, by the store's clock - a backoff before a retry. */
    case Expire

    /** Nothing: the store closed the entry, or another worker holds it now. */
    case Leave

  private def act(slot: Slot, next: Next): Unit =
    slot.renewing.set(false)
    if slot.leaseLost.get then ()
    else
      next match
        case Next.Close(outcome) =>
          queue.close(slot.lease.get, outcome).left.foreach { e =>
            logger.warn(s"Worker $holder: closing entry ${slot.entryId.value} failed: ${e.message}")
          }
        case Next.Release =>
          queue.release(slot.lease.get).left.foreach { e =>
            logger.debug(s"Worker $holder: releasing entry ${slot.entryId.value}: ${e.message}")
          }
        case Next.Expire | Next.Leave => ()

  private def decide(slot: Slot): Next =
    val entry = slot.entry
    val agent = agents(entry.agentId)
    if slot.leaseLost.get then Next.Leave
    else if slot.drained.get then Next.Release
    else
      liveClaim(entry) match
        case Left(error) =>
          logger.warn(s"Worker $holder: reading the claim of entry ${entry.id.value}'s thread failed: ${error.message}")
          Next.Expire
        case Right(true) =>
          // a live run holds the thread: defer to it, recording no attempt (W2)
          passOver(entry.id, settings.pollInterval)
          Next.Release
        case Right(false) =>
          read(entry) match
            case Left(error) =>
              logger.warn(s"Worker $holder: reading entry ${entry.id.value}'s thread failed: ${error.message}")
              Next.Expire
            case Right((admitted, latest)) =>
              val continues = AttemptPlan.of(entry, admitted, latest) match
                case AttemptPlan.Close(_) => Right(())
                case _                    => latest.fold[Result[Unit]](Right(()))(agent.agent.canContinue)
              continues match
                case Left(rejected: GraphError.RestoreRejected) => unrunnable(slot, rejected)
                case Left(error) =>
                  logger.warn(s"Worker $holder: entry ${entry.id.value}'s thread cannot be read: ${error.message}")
                  Next.Expire
                case Right(_) => attempt(slot, agent)

  /**
   * Whether a live claim holds `entry`'s thread, by the store's clock: a claim is asked for, as a probe that is given
   * back at once, so the store judges the claim it already holds, and no attempt is recorded for a thread that is busy.
   */
  private def liveClaim(entry: QueueEntry): Result[Boolean] =
    checkpointer.claim(entry.threadId, ClaimRequest(RunId.random(), entry.tenantId, ProbeTtl)) match
      case Left(_: GraphError.ThreadBusy) => Right(true)
      case Left(error)                    => Left(error)
      case Right(probe) =>
        checkpointer.release(entry.threadId, probe.token).left.foreach { e =>
          logger.debug(s"Worker $holder: releasing the probe claim on entry ${entry.id.value}'s thread: ${e.message}")
        }
        Right(false)

  /** The run-id lookup of `entry`'s runs, and the thread's latest checkpoint (rule 4). */
  private def read(entry: QueueEntry): Result[(Option[Checkpoint], Option[Checkpoint])] =
    for
      admitted <- checkpointer.latestOfRuns(entry.threadId, entry.runIds)
      latest   <- checkpointer.latest(entry.threadId)
    yield admitted -> latest.map(_.checkpoint)

  /**
   * An entry whose thread this worker's graph cannot take on: left for a worker whose graph can, passed over for a
   * while, until it has waited `settings.unrunnableAfter`, by the store's clock, since its submission or its latest
   * attempt; then closed `Failed(Unrunnable)`.
   */
  private def unrunnable(slot: Slot, rejected: GraphError.RestoreRejected): Next =
    val entry = slot.entry
    // the store's clock: the lease's expiry less its ttl is when the store granted or last renewed it
    val storeNow = slot.lease.get.expiresAt.minusNanos(lease.ttl.toNanos)
    val since    = entry.attempts.lastOption.fold(entry.submittedAt)(a => latestOf(a.startedAt, entry.submittedAt))
    if !storeNow.isBefore(since.plusNanos(settings.unrunnableAfter.toNanos)) then
      logger.warn(s"Worker $holder: entry ${entry.id.value} has no worker that can run it; closing it")
      Next.Close(
        EntryOutcome.Failed(
          FailureReason.Unrunnable(
            s"no worker's agent '${entry.agentId.value}' could take the thread on within ${settings.unrunnableAfter}: " +
              rejected.problems.mkString("; ")
          )
        )
      )
    else
      passOver(entry.id, lease.ttl)
      Next.Release

  private def attempt(slot: Slot, agent: QueuedAgent): Next =
    beforeAttempt(slot.entry)
    val runId = RunId.random()
    queue.recordAttempt(slot.lease.get, runId) match
      case Left(_: QueueError) => Next.Leave
      case Left(error) =>
        logger.warn(s"Worker $holder: recording an attempt at entry ${slot.entryId.value} failed: ${error.message}")
        Next.Expire
      case Right(AttemptStart.ThreadDeleted(_)) => Next.Leave
      case Right(AttemptStart.Exhausted(entry)) => exhausted(entry, agent)
      case Right(AttemptStart.Recorded(entry))  => run(slot, entry, agent, runId)

  /**
   * `entry` has made its `maxAttempts` attempts: closed `Failed(AttemptsExhausted)` - after its thread is settled, if
   * its input was admitted and its run is the thread's incomplete latest - or from the thread's state if it is at rest.
   */
  private def exhausted(entry: QueueEntry, agent: QueuedAgent): Next =
    val dead = EntryOutcome.Failed(FailureReason.AttemptsExhausted(entry.attempts.size))
    read(entry) match
      case Left(error) =>
        logger.warn(s"Worker $holder: reading entry ${entry.id.value}'s thread failed: ${error.message}")
        Next.Expire
      case Right((admitted, latest)) =>
        AttemptPlan.of(entry, admitted, latest) match
          case AttemptPlan.Close(outcome) => Next.Close(outcome)
          case AttemptPlan.Recover if admitted.isDefined =>
            val message = s"attempts exhausted: queue entry ${entry.id.value} made ${entry.attempts.size} attempts"
            val config  = RunConfig().withRunId(RunId.random()).withTenantId(entry.tenantId)
            agent.agent.settleQueued(
              entry.threadId,
              entry.runIds,
              RunOutcome.Failed("AttemptsExhausted", message),
              message,
              ExhaustedResult,
              config
            ) match
              case Right(_) => Next.Close(dead)
              case Left(_: GraphError.ThreadBusy) =>
                passOver(entry.id, settings.pollInterval)
                Next.Release
              case Left(error) =>
                logger.warn(s"Worker $holder: settling entry ${entry.id.value}'s thread failed: ${error.message}")
                Next.Expire
          case _ => Next.Close(dead)

  /** The attempt `runId` at `entry`, recorded: acts on [[AttemptPlan]], and on how the run it starts ends. */
  private def run(slot: Slot, entry: QueueEntry, agent: QueuedAgent, runId: RunId): Next =
    read(entry) match
      case Left(error) =>
        logger.warn(s"Worker $holder: reading entry ${entry.id.value}'s thread failed: ${error.message}")
        Next.Expire
      case Right((admitted, latest)) =>
        val config = attemptConfig(entry, runId)
        val begun: Either[EntryOutcome, Result[AgentRun]] = AttemptPlan.of(entry, admitted, latest) match
          case AttemptPlan.Close(outcome) => Left(outcome)
          case AttemptPlan.Recover        => Right(agent.agent.startRecover(entry.threadId, config))
          case AttemptPlan.Start =>
            Right(entry.input match
              case QueuedInput.Start(input) =>
                agent
                  .query(input)
                  .flatMap((query, history) => agent.agent.start(entry.threadId, query, config, history))
              case QueuedInput.Resume(answers) => agent.agent.startResume(entry.threadId, answers, config)
              case QueuedInput.Recover         => agent.agent.startRecover(entry.threadId, config)
            )
        begun match
          case Left(outcome)                         => Next.Close(outcome)
          case Right(Left(_: GraphError.ThreadBusy)) =>
            // a direct run took the thread between the probe and the admission: try again later
            passOver(entry.id, settings.pollInterval)
            Next.Release
          case Right(Left(_: CancelledError)) => Next.Release
          case Right(Left(error)) if LLMError.isRecoverable(error) =>
            logger.warn(s"Worker $holder: entry ${entry.id.value} could not be admitted: ${error.message}")
            Next.Expire
          case Right(Left(error)) => Next.Close(EntryOutcome.Failed(FailureReason.RunFailed(error.message)))
          case Right(Right(running)) =>
            slot.running(running)
            val result = running.await()
            ended(slot, entry, result)

  /**
   * After the attempt's run has exited: the entry closes from the thread's state when the run reached rest; a run the
   * drain cancelled is given back at once; any other is left for its lease to expire, so a later attempt recovers it.
   */
  private def ended(slot: Slot, entry: QueueEntry, result: Result[?]): Next =
    if slot.leaseLost.get then Next.Leave
    else
      // read the entry again: its `admitted` mark may have been set by the run's commit and its checkpoint retracted
      val now = queue.entry(entry.tenantId, entry.id).toOption.getOrElse(entry)
      read(now) match
        case Left(error) =>
          logger.warn(s"Worker $holder: reading entry ${entry.id.value}'s thread failed: ${error.message}")
          Next.Expire
        case Right((admitted, latest)) =>
          AttemptPlan.of(now, admitted, latest) match
            case AttemptPlan.Close(outcome) => Next.Close(outcome)
            case _ if slot.drained.get      => Next.Release
            case _ =>
              result.left.foreach(e =>
                logger.info(
                  s"Worker $holder: the run of entry ${entry.id.value} failed; it will be retried: ${e.message}"
                )
              )
              Next.Expire

  /**
   * The config attempt `runId` at `entry` runs with: its run id and the entry's tenant. #2213 adds the entry's trace
   * context here (`.withTraceContext(entry.traceContext)`), so every attempt of an entry is in its submitter's trace.
   */
  private def attemptConfig(entry: QueueEntry, runId: RunId): RunConfig =
    RunConfig().withRunId(runId).withTenantId(entry.tenantId)

  // ---- retention ----

  private def sweepLoop(every: FiniteDuration): Unit =
    @tailrec def next(): Unit =
      pause(every)
      if !stopRequested then
        sweepOnce().left.foreach(e => logger.warn(s"Worker $holder: the retention sweep failed: ${e.message}"))
        next()
    next()

  /**
   * One pass of the retention sweep, if no other instance is sweeping the store: every page of `Checkpointer.sweep` by
   * `settings.sweep`, its guard keeping each open entry's runs and its thread's tombstone ([[RunQueue.openRuns]]),
   * then every closed entry past the ticket lifetime (`RunQueue.reclaim`). Other instances are kept out by a store
   * claim on a reserved thread id, held and renewed while the pass runs; a pass that loses it stops.
   */
  private[service] def sweepOnce(): Result[SweepReport] =
    checkpointer.claim(SweepLock, ClaimRequest(RunId.random(), None, lease.ttl)) match
      case Left(_: GraphError.ThreadBusy) => Right(SweepReport(0, 0, 0, skipped = true))
      case Left(error)                    => Left(error)
      case Right(claim) =>
        val guard: SweepGuard = threads => queue.openRuns(threads)
        def held: Boolean     = checkpointer.renew(SweepLock, claim.token, lease.ttl).isRight
        @tailrec def pages(after: Option[ThreadId], examined: Int, tombstones: Int): Result[(Int, Int)] =
          checkpointer.sweep(settings.sweep, after, guard) match
            case Left(error) => Left(error)
            case Right(page) =>
              val (e, t) = (examined + page.examined, tombstones + page.reclaimed)
              page.next match
                case Some(cursor) if !stopRequested && held => pages(Some(cursor), e, t)
                case _                                      => Right(e -> t)
        @tailrec def reclaimed(total: Int): Result[Int] =
          queue.reclaim(queueSettings.ticketLifetime, settings.sweep.batchSize) match
            case Left(error)                                                         => Left(error)
            case Right(n) if n == settings.sweep.batchSize && !stopRequested && held => reclaimed(total + n)
            case Right(n)                                                            => Right(total + n)
        val pass =
          for
            swept   <- pages(None, 0, 0)
            entries <- reclaimed(0)
          yield SweepReport(swept._1, swept._2, entries, skipped = false)
        checkpointer.release(SweepLock, claim.token).left.foreach { e =>
          logger.debug(s"Worker $holder: releasing the sweep's claim: ${e.message}")
        }
        pass

  /** One entry the worker took: its lease, its run once started, and how it is being stopped. */
  final private class Slot(leased: LeasedEntry, val takenAt: Long):
    val entry: QueueEntry             = leased.entry
    val entryId: EntryId              = leased.entry.id
    val lease: AtomicReference[Lease] = new AtomicReference(leased.lease)
    val renewing: AtomicBoolean       = new AtomicBoolean(true)
    val leaseLost: AtomicBoolean      = new AtomicBoolean(false)
    val drained: AtomicBoolean        = new AtomicBoolean(false)
    val ended: CountDownLatch         = new CountDownLatch(1)
    private val run                   = new AtomicReference[Option[AgentRun]](None)

    /** The attempt's run has started: cancelled at once if the lease was lost or the drain cancelled it meanwhile. */
    def running(r: AgentRun): Unit =
      run.set(Some(r))
      if leaseLost.get || drained.get then r.cancel()

    /** The lease is gone: the run is cancelled, and nothing more is written under the lease. */
    def lost(why: String): Unit =
      leaseLost.set(true)
      renewing.set(false)
      logger.warn(s"Worker $holder: lost the lease of entry ${entryId.value} ($why); cancelling its run")
      run.get.foreach(_.cancel())

    /** The drain's cancel: the run is cancelled, and the entry given back once it has exited. */
    def cancelForDrain(): Unit =
      drained.set(true)
      run.get.foreach(_.cancel())

@Experimental
object AgentWorker:
  private val logger = LoggerFactory.getLogger(classOf[AgentWorker])

  /** How long the claim a worker asks for to judge whether a thread is busy lasts, if its release does not land. */
  private val ProbeTtl: FiniteDuration = 5.seconds

  /** The reserved thread id whose claim keeps two instances from sweeping one store at once. */
  val SweepLock: ThreadId = ThreadId("llm4s-worker-retention-sweep")

  /** The reserved thread id [[AgentWorker.health]] reads the store at: it has no checkpoints. */
  val HealthProbe: ThreadId = ThreadId("llm4s-worker-health")

  /** The synthetic result a tool call left unanswered gets when an exhausted entry's thread is settled. */
  val ExhaustedResult: String = "cancelled: the run's attempts ran out before this tool call returned a result"

  /**
   * A worker that runs `queue`'s entries for `agents` - each an id the entries name, its runtime over `checkpointer`,
   * the store `queue` is in - with `settings`, holding leases by `queueSettings.lease` and reclaiming closed entries
   * after `queueSettings.ticketLifetime`. Refuses no agents, and two with one id (`ValidationError`).
   */
  def apply(
    queue: RunQueue,
    checkpointer: Checkpointer,
    agents: Seq[QueuedAgent],
    settings: WorkerSettings = WorkerSettings.default,
    queueSettings: RunQueueSettings = RunQueueSettings.default
  ): Result[AgentWorker] =
    val repeated = agents.groupBy(_.id).collect { case (id, as) if as.size > 1 => id.value }.toVector.sorted
    if agents.isEmpty then Left(ValidationError("agents", "a worker needs at least one agent"))
    else if repeated.nonEmpty then Left(ValidationError("agents", s"agent ids given twice: ${repeated.mkString(", ")}"))
    else Right(new AgentWorker(queue, checkpointer, agents.map(a => a.id -> a).toMap, settings, queueSettings))

  private def awaitLatch(latch: CountDownLatch, deadline: Long): Boolean =
    Try(latch.await(math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)).getOrElse(false)

  private def latestOf(a: Instant, b: Instant): Instant = if a.isAfter(b) then a else b
