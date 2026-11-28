package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ RunId, TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result

import java.util.concurrent.TimeUnit
import scala.annotation.{ tailrec, unused }
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
 *    thread with an open entry of the submitter's tenant, or one a live claim of the submitter's tenant holds by the
 *    store's clock (a direct run, W3), with `GraphError.ThreadBusy` naming no holder - another tenant's entry or claim
 *    on the id is never reported (rule 9, #3085), so it neither refuses the submission nor tells it anything is
 *    there; when an `Interrupt` entry is submitted, close every queued `Interrupt`
 *    entry of the thread that has recorded no attempt `Interrupted(by = the new entry)`, answering them in
 *    [[Ticket.interrupted]], and ask every open entry of the thread that has recorded one to stop for it
 *    ([[StopRequest.Interrupt]]) unless a cancel was asked of it; refuse a submission whose tenant already has
 *    [[Submission.maxOpenPerThread]] open entries on the thread ([[QueueError.ThreadQueueFull]]) or whose input is
 *    longer than [[Submission.maxInputBytes]] ([[QueueError.InputTooLarge]], #3095); and
 *    issue entry ids in submission order, never reused;
 *  - '''order''' each thread's open entries (rule 5): an entry that has recorded an attempt first; then each tenant's
 *    entries together, the tenants in the order of their oldest open entry, so an `Interrupt` entry goes ahead of its
 *    own tenant's entries only and never takes a thread another tenant came to first (#3085); within a tenant
 *    `Interrupt` entries first, then the rest, each by id; only a thread's first entry in that order - its head - is
 *    ever taken, and only while no other entry of the thread holds a live lease; an entry's position
 *    ([[QueueEntry.position]], [[Ticket.position]]) counts only its own tenant's entries ahead of it (rule 9);
 *  - '''lease''' like a claim: [[take]] leases a head whose lease is absent or expired, by the store's clock, with a
 *    new [[LeaseToken]] greater than every one issued for the entry, and ends the expired lease of every other entry of
 *    the thread (a head an `Interrupt` entry displaced), so two workers never hold an actionable lease on one thread;
 *    a lease may be renewed, released, have attempts recorded under it and close its entry for as long as it is
 *    the entry's current one, expired or not, and every such call under another token is refused
 *    ([[QueueError.LeaseLost]]), as is any on a closed entry ([[QueueError.EntryClosed]]); taking is skip-locked
 *    where the store can, so workers do not contend for one entry, and locks no entry it does not lease (#3096);
 *  - '''read every entry it stored''' (#3091): an entry whose stored form cannot be decoded - written by a newer
 *    build, or by hand - is read back with what cannot be read replaced ([[QueuedInput.Unreadable]] for its input),
 *    so [[entry]], [[openEntries]], [[cancel]] and [[awaitClosed]] answer it; a [[take]] that reaches such an entry
 *    at a thread's head closes it `Failed(Unrunnable)`, naming what cannot be read, and goes on to the next head, so
 *    one such entry never blocks a take;
 *  - '''record attempts''' durably before answering ([[recordAttempt]]): an attempt's `RunId` naming no other run on
 *    the thread - no checkpoint of the thread's history and no attempt of any entry of the thread has it - so the
 *    run-id lookup finds only the attempt's own run; none for an entry that is no longer its thread's head - an
 *    `Interrupt` entry displaced it after its take - whose lease ends instead ([[QueueError.LeaseLost]], W3); no attempt past `maxAttempts` ([[AttemptStart.Exhausted]]); and
 *    none once the thread was deleted while the entry was open, closing the entry `Cancelled(ThreadDeleted)` instead
 *    ([[AttemptStart.ThreadDeleted]]);
 *  - '''learn of deletes''': the store's `Checkpointer.deleteThread` marks every open entry of the thread in the
 *    delete's own transaction (or under its lock), whatever the deleted thread numbered, so no such entry runs on a new
 *    thread of the id; an entry submitted after the delete is not marked. The store's `Checkpointer.commit` refuses a
 *    checkpoint whose run is an attempt a marked entry recorded - while the entry is open and after it closes, until
 *    it is reclaimed - so a worker whose claim outlived its lease never writes the deleted thread's input;
 *  - '''learn of admissions''': the store's `Checkpointer.commit` of a checkpoint whose run is an attempt of an open
 *    entry marks the entry [[QueueEntry.admitted]] in the commit's own transaction (or under its lock), and the mark
 *    stays when a later retraction removes the checkpoint, so [[AttemptPlan]] never starts an admitted input again;
 *  - '''keep the evidence''': while an entry is open, its store's `Checkpointer.prune` keeps the newest checkpoint each
 *    of the entry's recorded runs committed on its thread, so `latestOfRuns` never loses an admitted run;
 *  - '''cancel''' durably ([[cancel]], W3): close a targeted open entry that has recorded no attempt
 *    `Cancelled(reason)` at once, so it never runs, reporting it closed only to the call that closed it
 *    ([[Cancellation.closed]]), and ask one that has recorded an attempt to stop
 *    ([[StopRequest.Cancel]], replacing an interrupt), keeping the request until the entry closes and answering it with
 *    every lease granted or renewed on the entry ([[Lease.stop]]), so its worker - on any machine - sees it; closing
 *    an `Interrupt` entry that has not started withdraws the [[StopRequest.Interrupt]] it asked of the thread's other
 *    entries, in the same transaction (or under the same lock), so a cancelled interruption stops nothing (#3094);
 *  - '''close''' an entry once, with its [[EntryOutcome]], the store's time and the thread's last event number then
 *    ([[QueueEntry.closedAtSeq]]), and keep it for the ticket lifetime;
 *    [[reclaim]] removes closed entries older than that, oldest first, and never an open one; [[closeUntaken]]
 *    closes the heads no worker has taken for a given age (#3093);
 *  - '''scope by tenant''' (rule 9): [[entry]], [[awaitClosed]], [[openEntries]] and [[cancel]] answer for the caller's tenant's
 *    entries only (`None` and `Some` differ), another tenant's being as unknown as a reclaimed one; [[openRuns]],
 *    [[reclaim]], [[closeUntaken]] and [[allDepths]] are the operator's, store-wide;
 *  - return from every call within a bound it documents, as `Checkpointer` does: an entry's position is counted
 *    without reading other entries' inputs, and a thread holds at most [[Submission.maxOpenPerThread]] open entries
 *    per tenant (#3095).
 *
 * `RunQueueContract` in `llm4s-agent-testkit` is the executable form of this contract.
 */
@Experimental
trait RunQueue:

  /**
   * Queues `submission`, or finds the entry its key already names, and answers its [[Ticket]]. Refusals:
   * `GraphError.ThreadBusy` for a `Reject` submission to a thread with an open entry or a live claim of the
   * submitter's tenant, and [[QueueError.SubmissionKeyReused]].
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
   * `request.holder` for `request.ttl` from now, by the store's clock; `None` if there is none. The answer's
   * [[LeasedEntry.expiredLease]] says whether the head still held a lease, expired, that this take replaced.
   */
  def take(request: TakeRequest): Result[Option[LeasedEntry]]

  /**
   * For each of `agents` that has open entries, of any tenant, how many wait and how many are leased, and how long the
   * oldest waiting one has been open, by the store's clock ([[QueueDepth]]); an agent with none is absent. The
   * operator's, store-wide, as [[openRuns]] is: a worker samples it for its agents' queue-depth gauges (design 4.59).
   * It counts entries by an index on the agent, within the store's bound.
   */
  def depth(agents: Set[AgentId]): Result[Map[AgentId, QueueDepth]]

  /**
   * [[depth]] for every agent that has open entries, whether or not any worker asks for it (#3093): an entry submitted
   * for an agent no worker serves - a typo, or an agent removed in a deploy - is never taken, and shows here. The
   * operator's, store-wide; it counts entries by an index on the agent, within the store's bound.
   */
  def allDepths(): Result[Map[AgentId, QueueDepth]]

  /**
   * Closes `Failed(Unrunnable)` up to `limit` open entries, oldest first, that no worker has taken (#3093): each is its
   * thread's head, has recorded no attempt, holds no live lease, its thread holds no live claim, and was submitted
   * `age` or more ago, by the store's clock - so an entry of an agent no worker serves, which no worker ever takes,
   * does not keep its thread busy for ever. Closing an `Interrupt` entry withdraws its stop requests, as [[cancel]]
   * does. Answers the entries it closed. The store-wide retention sweep calls it with `WorkerSettings.unrunnableAfter`.
   */
  def closeUntaken(age: FiniteDuration, limit: Int): Result[Vector[QueueEntry]]

  /**
   * Extends `lease` to `ttl` from now, by the store's clock, answering it with the stop asked of the entry, if any
   * ([[Lease.stop]]); refused unless it is the entry's current lease.
   */
  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease]

  /**
   * Cancels `target` for `tenantId` (Stage 5 W3, design 4.57) and answers its entries as they are after: each open one
   * that has recorded no attempt is closed `Cancelled(reason)` at once, so no worker runs it; each that has recorded
   * one - running, or left by a worker that died - is asked to stop ([[StopRequest.Cancel]], replacing an
   * interrupt; a cancel already asked keeps its reason), and stays open until the worker holding it, or the next to
   * take it, stops its run, settles the thread and closes it. A closed entry is answered as it is.
   * [[Cancellation.closed]] names the entries this call closed, decided in the transaction (or under the lock) that
   * closed them: of concurrent cancels of one entry, exactly one reports it closed. `CancelTarget.Entry` answers
   * [[entry]]'s errors for an entry that is not the tenant's; `CancelTarget.Thread` cancels only the tenant's open
   * entries of the thread, and answers none for a thread with none.
   */
  def cancel(tenantId: Option[TenantId], target: CancelTarget, reason: CancelReason): Result[Cancellation]

  /**
   * Gives the entry back without closing it - as a worker that defers to a live run on the thread does, recording no
   * attempt - so it may be taken again at once. A lease that is not the current one changes nothing; a closed entry is
   * [[QueueError.EntryClosed]], so the worker knows it is terminal rather than requeued.
   */
  def release(lease: Lease): Result[Unit]

  /**
   * As [[release]], for an attempt a drain cut short (#3241): the entry is given back, and the attempt is not charged
   * against its budget - the entry's `maxAttempts` rises by one, and the attempt stays in its `attempts`, as history.
   * Internal to llm4s: only `AgentWorker` calls it, and only for a slot that recorded its own attempt under the lease it
   * releases, which is what keeps a refund to that attempt (the store does not check it: an attempt row does not name
   * its lease). An entry with no attempt recorded is released as by [[release]]. A worker's crash - its lease
   * lapsing with an attempt recorded under it - is given back by the take that finds it, and counted
   * ([[QueueEntry.crashes]], #3287), in every llm4s queue.
   * The default is [[release]].
   */
  private[llm4s] def releaseDrained(lease: Lease): Result[Unit] = release(lease)

  /**
   * Gives back an entry whose attempt failed, for a retry after `delay` (#3257): as [[release]], and no take hands the
   * entry out - nor any other entry of its thread - until `delay` has passed by the store's clock
   * ([[QueueEntry.retryAt]]). The attempt stays charged. Internal to llm4s: `AgentWorker` calls it with its backoff
   * (`WorkerSettings.retryDelay`, doubling per attempt). The default leaves the lease to lapse, so the entry comes
   * back a lease ttl later, as before.
   */
  private[llm4s] def releaseRetry(@unused lease: Lease, @unused delay: FiniteDuration): Result[Unit] = Right(())

  /**
   * The closed entries of `tenantId` in `state` - `done`, `failed` (the dead letters), `cancelled` or `interrupted`
   * ([[EntryOutcome.state]]) - oldest first by id, `limit` at a time, after entry `after` (#3257): an operator's view of
   * what has not run. Only the tenant's own entries are listed (rule 9); entries reclaimed by the ticket lifetime are
   * gone. An unknown state or a `limit` below 1 is a `ValidationError`. The default answers that this queue cannot list
   * its entries; every llm4s queue lists them.
   */
  def closedEntries(
    @unused tenantId: Option[TenantId],
    @unused state: String,
    @unused after: Option[EntryId],
    @unused limit: Int
  ): Result[Vector[QueueEntry]] =
    Left(ValidationError("queue", s"${getClass.getSimpleName} cannot list its closed entries"))

  /**
   * Re-drives a dead letter (#3257): submits entry `entryId`'s input again, as a new entry of the same thread, agent,
   * tenant and trace context, queued behind the thread's open entries (`Enqueue`) with `maxAttempts` attempts of its
   * own. The closed entry is left as it is. Only a `failed` entry is re-driven; an open entry, or one closed otherwise,
   * is a `ValidationError`, and so is one whose stored input cannot be read. A resume re-driven onto a thread that is no
   * longer waiting fails as a resume would.
   */
  def requeue(
    tenantId: Option[TenantId],
    entryId: EntryId,
    maxAttempts: Int = RunQueueSettings.DefaultMaxAttempts
  ): Result[Ticket] =
    entry(tenantId, entryId).flatMap { closed =>
      val refusal = closed.outcome match
        case None                         => Some(s"entry ${entryId.value} is still open")
        case Some(EntryOutcome.Failed(_)) => None
        case Some(other)                  => Some(s"entry ${entryId.value} closed ${other.state}, not failed")
      val unreadable = closed.input match
        case QueuedInput.Unreadable(_) => Some(s"entry ${entryId.value}'s input cannot be read")
        case _                         => None
      refusal.orElse(unreadable) match
        case Some(problem) => Left(ValidationError("entryId", problem))
        case None =>
          Submission
            .of(
              closed.threadId,
              closed.agentId,
              closed.input,
              closed.tenantId,
              BusyThreadPolicy.Enqueue,
              None,
              maxAttempts,
              closed.trace
            )
            .flatMap(submit)
    }

  /**
   * Records an attempt at `lease`'s entry running as `runId`, durably, before the caller touches the thread (rule 4);
   * see [[AttemptStart]]. Refused unless `lease` is current, and with a `ValidationError` for a `runId` that already
   * names a run on the thread - a checkpoint, or an attempt of any entry - since a reused `RunId` would name two runs
   * and the run-id lookup would take the other for this attempt's. A worker gives each attempt `RunId.random()`.
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

  /**
   * For each of `threads` that has an open entry - of any tenant: the operator's store-wide retention sweep asks it -
   * the [[org.llm4s.agent.graph.RunId RunId]]s of every attempt its open entries recorded (an empty set for open entries
   * that have recorded none); a thread with no open entry is absent. What the worker's sweep guard answers
   * (`org.llm4s.agent.graph.SweepGuard`, W2): the thread keeps its tombstone and the newest checkpoint of each of these
   * runs (rule 4). At most `SweepPolicy.MaxBatchSize` threads are asked at once.
   */
  def openRuns(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]]

@Experimental
object RunQueue:

  /** The states [[RunQueue.closedEntries]] lists by: those of [[EntryOutcome.state]]. */
  val ClosedStates: Set[String] = Set("done", "failed", "cancelled", "interrupted")

  /** Why a [[RunQueue.closedEntries]] call is refused, if it is. */
  private[llm4s] def closedEntriesRefusal(state: String, limit: Int): Option[ValidationError] =
    if !ClosedStates.contains(state) then
      Some(
        ValidationError(
          "state",
          s"'$state' is not a closed state: one of ${ClosedStates.toVector.sorted.mkString(", ")}"
        )
      )
    else if limit < 1 then Some(ValidationError("limit", s"limit must be at least 1, was $limit"))
    else None

  /** How often the default [[RunQueue.awaitClosed]] reads the entry. */
  val PollInterval: FiniteDuration = 100.millis

  /** The outcome [[RunQueue.closeUntaken]] closes an entry of `agent` with, after `age` untaken. */
  private[llm4s] def untakenOutcome(agent: AgentId, age: FiniteDuration): EntryOutcome =
    EntryOutcome.Failed(
      FailureReason.Unrunnable(
        s"no worker took the entry within $age of its submission: no worker serves agent '${agent.value}', " +
          "or none could take it"
      )
    )
