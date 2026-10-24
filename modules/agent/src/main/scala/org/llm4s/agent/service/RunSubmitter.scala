package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ Checkpointer, EventRecord, GraphError, GraphRuntime, RunConfig, TenantId, ThreadId }
import org.llm4s.agent.metrics.QueueMetrics
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.trace.TraceContext
import org.llm4s.types.Result

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.duration.*

/**
 * A page of a queued run's events ([[RunSubmitter.follow]]): `events`, those of the entry's attempts among the thread's
 * events after the cursor the caller passed, ascending; `next`, the cursor to pass next (the last thread event read,
 * whoever's it was); and `entry` as it was read after the events, so its attempts cover them. `finished` is `true` once
 * the entry is closed and every event up to its close ([[QueueEntry.closedAtSeq]]) has been read: nothing more will
 * come, however busy the thread stays with later runs.
 */
@Experimental
final case class FollowedRun(entry: QueueEntry, events: Vector[EventRecord], next: Long, finished: Boolean)

/**
 * The submission API (design 4.55): queue a run for a worker on any machine to take, wait for its ticket's terminal
 * state, or follow its events, over a store's [[RunQueue]] and the `Checkpointer` of the same store. Every call is
 * tenant-scoped (rule 9): a ticket is answered only to its own tenant.
 *
 * Entries are submitted with `settings.maxAttempts`; the worker side (W2) takes them. The entries this API closes
 * itself - one a cancel closes before it runs, an earlier unstarted `Interrupt` entry a new one closes - are counted on
 * `metrics` as the worker counts its closes (`llm4s.agent.queue.closed`, design 4.59; W3): those the store reports this
 * call closed ([[Ticket.interrupted]], [[Cancellation.closed]]), so a close two racing calls both saw is counted once.
 */
@Experimental
final class RunSubmitter(
  val queue: RunQueue,
  val checkpointer: Checkpointer,
  val settings: RunQueueSettings = RunQueueSettings.default,
  metrics: MetricsCollector = MetricsCollector.noop
):
  private val meter = new QueueMetrics(metrics)

  /**
   * Queues `input` for `threadId`, run by `agentId` for `tenantId`, and answers its ticket - or, with a `submissionKey`
   * the tenant's queue already holds for the same submission, that entry's ticket. See [[RunQueue.submit]].
   * `traceContext` is the submitter's trace position, which every attempt continues ([[Submission.traceContext]]).
   */
  def submit(
    threadId: ThreadId,
    agentId: AgentId,
    input: QueuedInput,
    tenantId: Option[TenantId] = None,
    policy: BusyThreadPolicy = BusyThreadPolicy.Reject,
    submissionKey: Option[SubmissionKey] = None,
    traceContext: Option[TraceContext] = None
  ): Result[Ticket] =
    // an Interrupt closes the thread's earlier unstarted Interrupt entries in the store, which answers them in the
    // ticket - only to the submit that closed them - and they are counted here (W3, #2368)
    Submission
      .of(threadId, agentId, input, tenantId, policy, submissionKey, settings.maxAttempts, traceContext)
      .flatMap(queue.submit)
      .map { ticket =>
        countClosed(ticket.interrupted)
        ticket
      }

  /**
   * Cancels `target` - one entry, or every open entry of a thread - for `tenantId`, from any machine (W3, design 4.57),
   * and answers its entries as they are after: one not yet started is closed `Cancelled(Requested(note))` before it
   * runs; a running one is stopped by the worker holding it, which learns of the cancel when it next renews the entry's
   * lease, waits for the run to exit, settles the thread and closes the entry - `Cancelled`, or with the run's own
   * outcome if it reached rest first. [[await]] its ticket for the close. See [[RunQueue.cancel]].
   */
  def cancel(
    tenantId: Option[TenantId],
    target: CancelTarget,
    note: Option[String] = None
  ): Result[Vector[QueueEntry]] = cancelled(tenantId, target, CancelReason.Requested(note))

  /** [[RunQueue.cancel]], counting the entries the store reports it closed before they ran. */
  private def cancelled(
    tenantId: Option[TenantId],
    target: CancelTarget,
    reason: CancelReason
  ): Result[Vector[QueueEntry]] =
    queue.cancel(tenantId, target, reason).map { cancellation =>
      countClosed(cancellation.closedEntries)
      cancellation.entries
    }

  /** Counts each of `entries`, which the store reports the call closed. */
  private def countClosed(entries: Vector[QueueEntry]): Unit =
    entries.foreach(e => e.outcome.foreach(meter.closed(e.agentId, _)))

  /**
   * The service's delete of `threadId` for `tenantId` (W3, Stage 5 rule 4): it closes the thread's queued entries,
   * cancels its running one - `Cancelled(ThreadDeleted)` - and waits up to `timeout` for every one of them to close,
   * which the worker running one does only once its run has exited and the thread is settled; then it deletes the
   * thread through `runtime` (`GraphRuntime.deleteThread`, which claims the thread and deletes its children first). An
   * entry still open after `timeout` - its run has not exited - leaves the thread undeleted: `GraphError.ThreadBusy`,
   * and calling again finishes it. An entry submitted meanwhile is marked by the delete itself and closes
   * `Cancelled(ThreadDeleted)` without running (rule 4). Errors are the queue's, and the runtime's delete's.
   */
  def deleteThread(
    runtime: GraphRuntime,
    tenantId: Option[TenantId],
    threadId: ThreadId,
    timeout: FiniteDuration
  ): Result[Unit] =
    val deadline                  = System.nanoTime() + timeout.toNanos
    def remaining: FiniteDuration = math.max(0L, deadline - System.nanoTime()).nanos
    cancelled(tenantId, CancelTarget.Thread(threadId), CancelReason.ThreadDeleted).flatMap { cancelled =>
      val waited = cancelled.filter(_.isOpen).foldLeft[Result[Boolean]](Right(true)) { (done, open) =>
        done.flatMap(all => queue.awaitClosed(tenantId, open.id, remaining).map(e => all && !e.isOpen))
      }
      waited.flatMap { allClosed =>
        if allClosed then runtime.deleteThread(threadId, RunConfig(tenantId = tenantId))
        else Left(GraphError.ThreadBusy(threadId.value, None, GraphError.BusyHolder.Unnamed))
      }
    }

  /** The entry `entryId` of `tenantId`, as it is now. */
  def entry(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] = queue.entry(tenantId, entryId)

  /**
   * Entry `entryId` once it is closed, or as it is after `timeout`; [[QueueError.TicketExpired]] once it has been
   * reclaimed. See [[RunQueue.awaitClosed]].
   */
  def await(tenantId: Option[TenantId], entryId: EntryId, timeout: FiniteDuration): Result[QueueEntry] =
    queue.awaitClosed(tenantId, entryId, timeout)

  /**
   * The next page of entry `entryId`'s run events after thread event `after` (`0` from the thread's first), at most
   * `limit`, waiting up to `timeout` for a commit when there is nothing yet - through `Checkpointer.awaitEventsAfter`,
   * so a run committed by a worker on another machine is followed as it lands - or for the entry to close, through
   * [[RunQueue.awaitClosed]], since a close commits no event: a follower is told it has `finished` as soon as the entry
   * closes, not once `timeout` has passed. Pass each page's `next` to the next
   * call until `finished`. `limit` below 1 is a `ValidationError`. Errors are the queue's for the ticket and the store's for the thread's log
   * (`GraphError.ReplayUnavailable`, `GraphError.ThreadDeleted`).
   */
  def follow(
    tenantId: Option[TenantId],
    entryId: EntryId,
    after: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[FollowedRun] =
    if limit < 1 then Left(ValidationError("limit", s"must be at least 1, was $limit"))
    else
      queue.entry(tenantId, entryId).flatMap { before =>
        before.closedAtSeq match
          // closed: every event of its runs is at or below the thread's last event at the close, so read no further
          case Some(end) =>
            val read =
              if after >= end then Right(Vector.empty) else checkpointer.eventsAfter(before.threadId, after, limit)
            read.map(page(before, after, limit, _))
          case None =>
            awaitEventsOrClose(tenantId, before, after, limit, timeout).flatMap { read =>
              // read again after the events: an attempt is recorded before it commits, so these attempts cover every
              // event. If the entry closed meanwhile, its last events may have landed after `read`: read the log again,
              // now bounded by the close, before the page may say it is finished
              queue.entry(tenantId, entryId).flatMap { now =>
                now.closedAtSeq match
                  case Some(end) if after < end =>
                    checkpointer.eventsAfter(now.threadId, after, limit).map(page(now, after, limit, _))
                  case _ => Right(page(now, after, limit, read))
              }
            }
      }

  /**
   * The thread's events after `after`, at most `limit`, once there are some, or empty once `open` closes or `timeout`
   * passes, whichever is first. The two waits run on threads of their own, and the first to answer interrupts the
   * other, as `Checkpointer.awaitEventsAfter` and [[RunQueue.awaitClosed]] both allow; the calling thread is never
   * interrupted. Interrupting the calling thread ends both waits with `Left(CancelledError)`, its flag set again.
   */
  private def awaitEventsOrClose(
    tenantId: Option[TenantId],
    open: QueueEntry,
    after: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] =
    // Left: the queue's answer; Right: the store's. `offer` never blocks, so an interrupted waiter still reports
    val answers = new LinkedBlockingQueue[Either[Result[QueueEntry], Result[Vector[EventRecord]]]]()
    val name    = s"llm4s-follow-${open.id.value}"
    val reader = Thread.ofVirtual().name(s"$name-events").start { () =>
      answers.offer(Right(checkpointer.awaitEventsAfter(open.threadId, after, limit, timeout))): Unit
    }
    val closer = Thread.ofVirtual().name(s"$name-close").start { () =>
      answers.offer(Left(queue.awaitClosed(tenantId, open.id, timeout))): Unit
    }
    @tailrec def first(): Result[Vector[EventRecord]] =
      CancelledError.catchInterrupt(answers.take()) match
        case Left(interrupted) =>
          reader.interrupt()
          closer.interrupt()
          Thread.currentThread().interrupt()
          Left(CancelledError(s"following queue entry ${open.id.value}", Some(interrupted)))
        case Right(Right(read)) =>
          closer.interrupt()
          read
        // the queue's wait timed out with the entry open: the store's answer is due within its own bound
        case Right(Left(Right(still))) if still.isOpen => first()
        // closed (or gone): the caller reads the entry and the log again, bounded by the close
        case Right(Left(closed)) =>
          reader.interrupt()
          closed.map(_ => Vector.empty)
    first()

  /** The page `raw` - the thread's events after `after`, at most `limit` - makes for `entry`. */
  private def page(entry: QueueEntry, after: Long, limit: Int, raw: Vector[EventRecord]): FollowedRun =
    val runs = entry.runIds.map(_.value)
    entry.closedAtSeq match
      case Some(end) =>
        val done = raw.size < limit || raw.lastOption.exists(_.seq >= end)
        FollowedRun(
          entry,
          raw.filter(e => e.seq <= end && runs(e.runId)),
          if done then math.max(after, end) else raw.last.seq,
          finished = done
        )
      case None =>
        FollowedRun(entry, raw.filter(e => runs(e.runId)), raw.lastOption.fold(after)(_.seq), finished = false)
