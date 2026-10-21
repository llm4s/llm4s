package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ Checkpointer, EventRecord, TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

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
 * Entries are submitted with `settings.maxAttempts`; the worker side (W2) takes them.
 */
@Experimental
final class RunSubmitter(
  val queue: RunQueue,
  val checkpointer: Checkpointer,
  val settings: RunQueueSettings = RunQueueSettings.default
):

  /**
   * Queues `input` for `threadId`, run by `agentId` for `tenantId`, and answers its ticket - or, with a `submissionKey`
   * the tenant's queue already holds for the same submission, that entry's ticket. See [[RunQueue.submit]].
   */
  def submit(
    threadId: ThreadId,
    agentId: AgentId,
    input: QueuedInput,
    tenantId: Option[TenantId] = None,
    policy: BusyThreadPolicy = BusyThreadPolicy.Reject,
    submissionKey: Option[SubmissionKey] = None
  ): Result[Ticket] =
    Submission
      .of(threadId, agentId, input, tenantId, policy, submissionKey, settings.maxAttempts)
      .flatMap(queue.submit)

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
   * so a run committed by a worker on another machine is followed as it lands. Pass each page's `next` to the next
   * call until `finished`. Errors are the queue's for the ticket and the store's for the thread's log
   * (`GraphError.ReplayUnavailable`, `GraphError.ThreadDeleted`).
   */
  def follow(
    tenantId: Option[TenantId],
    entryId: EntryId,
    after: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[FollowedRun] =
    queue.entry(tenantId, entryId).flatMap { before =>
      before.closedAtSeq match
        // closed: every event of its runs is at or below the thread's last event at the close, so read no further
        case Some(end) =>
          val read =
            if after >= end then Right(Vector.empty) else checkpointer.eventsAfter(before.threadId, after, limit)
          read.map { raw =>
            val runs = before.runIds.map(_.value)
            val done = raw.size < limit || raw.lastOption.exists(_.seq >= end)
            FollowedRun(
              before,
              raw.filter(e => e.seq <= end && runs(e.runId)),
              if done then math.max(after, end) else raw.last.seq,
              finished = done
            )
          }
        case None =>
          checkpointer.awaitEventsAfter(before.threadId, after, limit, timeout).flatMap { read =>
            // read again after the events: an attempt is recorded before it commits, so these attempts cover every event
            queue.entry(tenantId, entryId).map { now =>
              val runs = now.runIds.map(_.value)
              FollowedRun(now, read.filter(e => runs(e.runId)), read.lastOption.fold(after)(_.seq), finished = false)
            }
          }
    }
