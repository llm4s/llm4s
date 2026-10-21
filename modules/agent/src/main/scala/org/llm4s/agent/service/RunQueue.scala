package org.llm4s.agent.service

import org.llm4s.agent.graph.{ RunId, TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.error.CancelledError
import org.llm4s.types.Result

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*

/**
 * The durable run queue (Stage 5 W1, design 4.55): submitted runs, one entry each, that workers on any machine take,
 * run and close. A store's queue lives in the same database as its checkpoints - `InMemoryCheckpointer.runQueue`,
 * `SqliteCheckpointer.runQueue`, `PostgresCheckpointer.runQueue` - but is a separate SPI: no transaction spans an
 * entry and a commit. Correctness rests on rule 4 instead - every attempt has its own [[org.llm4s.agent.graph.RunId RunId]], recorded here
 * before the attempt touches the thread, and an attempt classifies the thread by those ids
 * (`Checkpointer.latestOfRuns`, [[AttemptPlan]]) - so an input is applied at most once however many attempts run.
 *
 * Implementations must be safe to call from several threads and processes, and must:
 *
 *  - '''submit''' an entry atomically: look up its [[SubmissionKey]], if any, among the tenant's entries and insert
 *    only if there is none, so a retried submit gets the first one's ticket ([[Ticket.existing]]) and a different
 *    submission under a held key is refused ([[QueueError.SubmissionKeyReused]]); refuse a `Reject` submission to a
 *    thread with an open entry with `GraphError.ThreadBusy` naming no holder; close every queued `Interrupt` entry of
 *    the thread that has recorded no attempt `Interrupted(by = the new entry)` when an `Interrupt` entry is submitted;
 *    issue entry ids in submission order, never reused; and record the thread's deletion marker - where the event
 *    numbering of the thread now under the id starts, which a `deleteThread` moves on - so a later delete is seen;
 *  - '''order''' each thread's open entries (rule 5): an entry that has recorded an attempt first, then `Interrupt`
 *    entries, then the rest, each by id; only a thread's first entry in that order - its head - is ever taken, and only
 *    while no other entry of the thread holds a live lease;
 *  - '''lease''' like a claim: [[take]] leases a head whose lease is absent or expired, by the store's clock, with a
 *    new [[LeaseToken]] greater than every one issued for the entry, so two workers never hold a live lease on one
 *    thread; a lease may be renewed, released, have attempts recorded under it and close its entry for as long as it is
 *    the entry's current one, expired or not, and every such call under another token is refused
 *    ([[QueueError.LeaseLost]]), as is any on a closed entry ([[QueueError.EntryClosed]]); taking is skip-locked
 *    where the store can, so workers do not contend for one entry;
 *  - '''record attempts''' durably before answering ([[recordAttempt]]): an attempt's `RunId` never reused within the
 *    entry, no attempt past `maxAttempts` ([[AttemptStart.Exhausted]]), and none after the thread was deleted - the
 *    deletion marker moved on since submission - closing the entry `Cancelled(ThreadDeleted)` instead
 *    ([[AttemptStart.ThreadDeleted]]);
 *  - '''keep the evidence''': while an entry is open, its store's `Checkpointer.prune` keeps the newest checkpoint each
 *    of the entry's recorded runs committed on its thread, so `latestOfRuns` never loses an admitted run;
 *  - '''close''' an entry once, with its [[EntryOutcome]] and the store's time, and keep it for the ticket lifetime;
 *    [[reclaim]] removes closed entries older than that, oldest first, and never an open one;
 *  - '''scope by tenant''' (rule 9): [[entry]], [[awaitClosed]] and [[openEntries]] answer for the caller's tenant's
 *    entries only (`None` and `Some` differ), another tenant's being as unknown as a reclaimed one;
 *  - return from every call within a bound it documents, as `Checkpointer` does.
 *
 * `RunQueueContract` in `llm4s-agent-testkit` is the executable form of this contract.
 */
@Experimental
trait RunQueue:

  /**
   * Queues `submission`, or finds the entry its key already names, and answers its [[Ticket]]. Refusals:
   * `GraphError.ThreadBusy` for a `Reject` submission to a thread with an open entry, and
   * [[QueueError.SubmissionKeyReused]].
   */
  def submit(submission: Submission): Result[Ticket]

  /**
   * Entry `entryId`, if it is `tenantId`'s: [[QueueError.TicketExpired]] for an id the store issued and no longer holds
   * for the tenant (reclaimed, or another tenant's), [[QueueError.TicketNotFound]] for one it never issued.
   */
  def entry(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry]

  /**
   * Entry `entryId` once it is closed, or as it is when `timeout` passes - whichever is first; errors are [[entry]]'s.
   * The default polls [[entry]] every 100 milliseconds; a store that is told of closes overrides it. Interrupting the
   * waiting thread ends the wait with `Left(CancelledError)`, its interrupt flag set again.
   */
  def awaitClosed(tenantId: Option[TenantId], entryId: EntryId, timeout: FiniteDuration): Result[QueueEntry] =
    val deadline = System.nanoTime() + timeout.toNanos
    @tailrec def poll(): Result[QueueEntry] =
      entry(tenantId, entryId) match
        case Right(open) if open.isOpen && System.nanoTime() < deadline =>
          val pause = math.min(RunQueue.PollInterval.toNanos, math.max(deadline - System.nanoTime(), 0L))
          CancelledError.catchInterrupt(TimeUnit.NANOSECONDS.sleep(pause)) match
            case Right(_) => poll()
            case Left(interrupted) =>
              Thread.currentThread().interrupt()
              Left(CancelledError(s"waiting for queue entry ${entryId.value}", Some(interrupted)))
        case other => other
    poll()

  /** `tenantId`'s open entries on `threadId`, in the order they run (see the class doc); empty for an unknown thread. */
  def openEntries(tenantId: Option[TenantId], threadId: ThreadId): Result[Vector[QueueEntry]]

  /**
   * Leases the oldest takeable head - see the class doc - whose agent is one of `request.agents`, to
   * `request.holder` for `request.ttl` from now, by the store's clock; `None` if there is none.
   */
  def take(request: TakeRequest): Result[Option[LeasedEntry]]

  /** Extends `lease` to `ttl` from now, by the store's clock; refused unless it is the entry's current lease. */
  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease]

  /**
   * Gives the entry back without closing it - as a worker that defers to a live run on the thread does, recording no
   * attempt - so it may be taken again at once. A lease that is not the current one changes nothing.
   */
  def release(lease: Lease): Result[Unit]

  /**
   * Records an attempt at `lease`'s entry running as `runId`, durably, before the caller touches the thread (rule 4);
   * see [[AttemptStart]]. Refused unless `lease` is current, and for a `runId` the entry already recorded
   * (`ValidationError`): a reused `RunId` would name two runs.
   */
  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart]

  /** Closes `lease`'s entry with `outcome` and answers it closed; refused unless `lease` is current. */
  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry]

  /**
   * Removes up to `limit` closed entries whose close is `ticketLifetime` or more ago, by the store's clock, oldest
   * first, input and attempts included, and answers how many it removed; never an open entry. The store-wide
   * retention sweep (W2) calls it.
   */
  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int]

@Experimental
object RunQueue:

  /** How often the default [[RunQueue.awaitClosed]] reads the entry. */
  val PollInterval: FiniteDuration = 100.millis
