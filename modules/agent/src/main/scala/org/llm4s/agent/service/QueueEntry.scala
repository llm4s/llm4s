package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ CheckpointStatus, RunConfig, RunId, TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.trace.TraceContext

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * How a submission treats a thread that already has an open entry or a live run (Stage 5 W1 and W3, designs 4.55 and
 * 4.57), reversing §5.3's "do not queue".
 *
 *  - `Reject`, the default: refused with `GraphError.ThreadBusy` while the thread has an open entry or a live claim -
 *    a run there, queued or direct, on any machine - as a direct run on a busy thread is refused (§5.3).
 *  - `Enqueue`: waits behind the thread's running and queued entries, in submission order (W1's FIFO); behind a direct
 *    run, the worker that takes it waits for the run's claim.
 *  - `Interrupt`: goes ahead of the thread's queued entries, which keep their order behind it, and asks the thread's
 *    running entry to stop at its next superstep boundary: its model call in flight is cut, tool calls already started
 *    finish and keep their results, none not yet started starts, the turn is closed with a synthetic "interrupted"
 *    result for each call left without one, and the entry closes [[EntryOutcome.Interrupted]], not retried. Of two
 *    `Interrupt` entries the later goes first and the earlier is closed `Interrupted` before it runs (Stage 5 rule 5).
 *    A direct run is not interrupted - it is its owner's - so the entry waits for it, as `Enqueue` does.
 *
 * A pending approval or question on the thread is not a busy thread, whatever the policy: the thread is at rest, and a
 * queued start is refused by the runtime while the interrupts are pending (§5.3) - a resume is what answers them.
 */
@Experimental
enum BusyThreadPolicy derives upickle.default.ReadWriter:
  case Reject
  case Enqueue
  case Interrupt

/**
 * One attempt at an entry's run (Stage 5 rule 4): the [[org.llm4s.agent.graph.RunId RunId]] the attempt runs as, recorded durably before the
 * attempt touches the thread, when the store recorded it, by its clock, and the lease holder that recorded it.
 */
@Experimental
final case class Attempt(runId: RunId, startedAt: Instant, holder: String)

/**
 * Why an entry closed `failed` (design 4.55).
 *
 *  - `AttemptsExhausted`: `attempts` attempts ran and none finished (a dead letter). If the input was admitted, the
 *    worker that found the attempts exhausted settled the thread first, so later entries start.
 *  - `RunFailed`: the run ended in failure - a terminal `Failed` checkpoint, or a refusal such as `NotSuspended` for a
 *    resume of a thread that is not waiting - with the error's message.
 *  - `Unrunnable`: no worker could run the entry (no worker has its agent at a graph version that can restore its
 *    thread, W2), with why.
 */
@Experimental
enum FailureReason:
  case AttemptsExhausted(attempts: Int)
  case RunFailed(message: String)
  case Unrunnable(message: String)

/**
 * Why an entry closed `cancelled` (design 4.55). `ThreadDeleted`: the thread was deleted after the entry was
 * submitted, so the entry ran nothing (a deleted thread is never resurrected, rule 4) - or a service's delete of the
 * thread cancelled it first (`RunSubmitter.deleteThread`, W3). `Requested`: a cancel request stopped it (W3), with the
 * requester's note.
 */
@Experimental
enum CancelReason:
  case ThreadDeleted
  case Requested(note: Option[String])

/**
 * A stop asked of an open entry that has recorded an attempt (Stage 5 W3, design 4.57), which the worker running it sees
 * when it next renews the entry's lease ([[Lease.stop]]), or when it takes the entry.
 *
 *  - `Cancel`: stop now, as `AgentRun.cancel` stops a run, settle the thread, and close the entry `Cancelled(reason)` -
 *    or with the run's own outcome if it reached rest first. Asked by [[RunQueue.cancel]]; it replaces an `Interrupt`.
 *  - `Interrupt`: stop at the next superstep boundary for the `Interrupt` entry `by`, settle the thread, and close
 *    `Interrupted(by)`. Asked by an `Interrupt` submission to the thread; a later one replaces `by`.
 */
@Experimental
enum StopRequest:
  case Cancel(reason: CancelReason)
  case Interrupt(by: EntryId)

  /** The outcome an entry stopped by this request closes with. */
  def outcome: EntryOutcome = this match
    case Cancel(reason) => EntryOutcome.Cancelled(reason)
    case Interrupt(by)  => EntryOutcome.Interrupted(Some(by))

@Experimental
object StopRequest:

  /** The request as an entry stores it: its outcome's JSON. */
  private[llm4s] def toJson(request: StopRequest): ujson.Value = EntryOutcome.toJson(request.outcome)

  /** The request [[toJson]] stored, or why it cannot be read. */
  private[llm4s] def fromJson(json: ujson.Value): Either[String, StopRequest] =
    EntryOutcome.fromJson(json).flatMap {
      case EntryOutcome.Cancelled(reason)    => Right(Cancel(reason))
      case EntryOutcome.Interrupted(Some(b)) => Right(Interrupt(b))
      case other                             => Left(s"not a stop request: ${other.state}")
    }

/**
 * What [[RunQueue.cancel]] cancels (Stage 5 W3, design 4.57): one entry, by its ticket, or every open entry of a
 * thread - the running one and the queued ones.
 */
@Experimental
enum CancelTarget:
  case Entry(entryId: EntryId)
  case Thread(threadId: ThreadId)

/**
 * What [[RunQueue.cancel]] answers (Stage 5 W3, design 4.57): the targeted `entries` as they are after the cancel, and
 * the ids of those this call `closed` - the ones it found open with no attempt and closed `Cancelled` itself, decided in
 * the store's closing transaction (or under its lock). An entry another cancel closed first, one already closed and
 * one asked to stop are in `entries` but not in `closed`, so of two cancels racing for one entry exactly one reports
 * it: what [[RunSubmitter]] counts as `llm4s.agent.queue.closed` (design 4.59, #2368).
 */
@Experimental
final case class Cancellation(entries: Vector[QueueEntry], closed: Set[EntryId]):

  /** The entries this call closed, in `entries`' order. */
  def closedEntries: Vector[QueueEntry] = entries.filter(e => closed(e.id))

/**
 * How an entry closed: its terminal state (design 4.55).
 *
 *  - `Done`: the entry's run reached rest - completed or suspended - or the thread had moved on past the entry's
 *    admitted input (rule 4), at checkpoint `checkpointId` with `status`, if the thread had one.
 *  - `Failed`: see [[FailureReason]].
 *  - `Cancelled`: see [[CancelReason]].
 *  - `Interrupted`: an `Interrupt` entry `by` superseded it - before it ran, for a queued `Interrupt` entry, or at its
 *    next safe point (W3). It is not retried.
 */
@Experimental
enum EntryOutcome:
  case Done(checkpointId: Option[String], status: Option[CheckpointStatus])
  case Failed(reason: FailureReason)
  case Cancelled(reason: CancelReason)
  case Interrupted(by: Option[EntryId])

  /** `done`, `failed`, `cancelled` or `interrupted`: the entry's state, as a store indexes it. */
  def state: String = this match
    case Done(_, _)     => "done"
    case Failed(_)      => "failed"
    case Cancelled(_)   => "cancelled"
    case Interrupted(_) => "interrupted"

@Experimental
object EntryOutcome:

  /** The outcome as an entry stores it. */
  private[llm4s] def toJson(outcome: EntryOutcome): ujson.Value = outcome match
    case Done(checkpointId, status) =>
      val json = ujson.Obj("state" -> "done")
      checkpointId.foreach(json("checkpointId") = _)
      status.foreach(s => json("status") = s.toString)
      json
    case Failed(reason) =>
      reason match
        case FailureReason.AttemptsExhausted(n) =>
          ujson.Obj("state" -> "failed", "reason" -> "attemptsExhausted", "attempts" -> n)
        case FailureReason.RunFailed(message) =>
          ujson.Obj("state" -> "failed", "reason" -> "runFailed", "message" -> message)
        case FailureReason.Unrunnable(message) =>
          ujson.Obj("state" -> "failed", "reason" -> "unrunnable", "message" -> message)
    case Cancelled(CancelReason.ThreadDeleted) => ujson.Obj("state" -> "cancelled", "reason" -> "threadDeleted")
    case Cancelled(CancelReason.Requested(note)) =>
      val json = ujson.Obj("state" -> "cancelled", "reason" -> "requested")
      note.foreach(json("note") = _)
      json
    case Interrupted(by) =>
      val json = ujson.Obj("state" -> "interrupted")
      by.foreach(id => json("by") = ujson.Num(id.value.toDouble))
      json

  /** The outcome [[toJson]] stored, or why it cannot be read. */
  private[llm4s] def fromJson(json: ujson.Value): Either[String, EntryOutcome] =
    Try {
      val o                                = json.obj
      def str(key: String): String         = o(key).str
      def opt(key: String): Option[String] = o.get(key).map(_.str)
      (str("state"), opt("reason")) match
        case ("done", _) =>
          Done(opt("checkpointId"), opt("status").map(s => CheckpointStatus.valueOf(s)))
        case ("failed", Some("attemptsExhausted")) => Failed(FailureReason.AttemptsExhausted(o("attempts").num.toInt))
        case ("failed", Some("runFailed"))         => Failed(FailureReason.RunFailed(str("message")))
        case ("failed", Some("unrunnable"))        => Failed(FailureReason.Unrunnable(str("message")))
        case ("cancelled", Some("threadDeleted"))  => Cancelled(CancelReason.ThreadDeleted)
        case ("cancelled", Some("requested"))      => Cancelled(CancelReason.Requested(opt("note")))
        case ("interrupted", _)                    => Interrupted(o.get("by").map(b => EntryId.unsafe(b.num.toLong)))
        case (state, reason) => throw new IllegalArgumentException(s"unknown outcome $state/${reason.getOrElse("")}")
    }.toEither.left.map(e => s"not an entry outcome: ${e.getMessage}")

/**
 * A run queue entry, as a store reads it (design 4.55): one submitted run on a thread, durable, with the attempts made
 * at it and, once closed, its [[EntryOutcome]].
 *
 * An entry is open until it is closed, and only an open entry is ever taken or reclaimed-proof: a closed one is kept
 * for the ticket lifetime after `closedAt`, then reclaimed. While open, `position` is how many of the thread's open
 * entries run before it (`0`: it runs next, or is running), and `leaseHolder` and `leaseExpiresAt` say which worker
 * holds it, if one does - a lease that has expired by the store's clock may be taken over.
 *
 * @param maxAttempts the most attempts the entry may make, fixed at submission
 * @param trace the submitter's trace context ([[Submission.traceContext]]), which every attempt continues
 *   ([[attemptConfig]]); `None` when the submission named none
 * @param attempts the attempts recorded, oldest first, each before it touched the thread
 * @param admitted whether a run of one of `attempts` has committed a checkpoint on the thread - set by the store in
 *   that commit's own transaction, and kept when the checkpoint later leaves the history (a direct run's
 *   `Commit.retractTurn` removes it), so the input is known to be admitted even when the run-id lookup no longer
 *   finds it ([[AttemptPlan]])
 * @param closedAtSeq the number of the thread's last event when the entry closed (`0` for none): every event of the
 *   entry's runs is at or below it, so following the entry stops there ([[RunSubmitter.follow]])
 * @param stopRequest the stop asked of the entry's running attempt, if any (W3): a cancel, or an interrupt by a later
 *   `Interrupt` entry; kept until the entry closes
 */
@Experimental
final case class QueueEntry private (
  id: EntryId,
  threadId: ThreadId,
  agentId: AgentId,
  tenantId: Option[TenantId],
  input: QueuedInput,
  policy: BusyThreadPolicy,
  submissionKey: Option[SubmissionKey],
  maxAttempts: Int,
  trace: Option[TraceContext],
  submittedAt: Instant,
  attempts: Vector[Attempt],
  admitted: Boolean,
  leaseHolder: Option[String],
  leaseExpiresAt: Option[Instant],
  outcome: Option[EntryOutcome],
  closedAt: Option[Instant],
  closedAtSeq: Option[Long],
  position: Option[Int],
  stopRequest: Option[StopRequest]
):
  /** Whether the entry is still to run or running: it has no outcome. */
  def isOpen: Boolean = outcome.isEmpty

  /**
   * The `RunConfig` an attempt at the entry runs with (Stage 5 rule 4, design 4.58): as run `runId` - the attempt's,
   * recorded by [[RunQueue.recordAttempt]] - for the entry's tenant, continuing the submitter's trace context
   * ([[trace]]), so every attempt of the entry is in the submitter's trace whatever worker or machine runs it. A
   * recover or resume of a thread whose entry has no `trace` continues the trace its checkpoint records.
   */
  def attemptConfig(runId: RunId): RunConfig =
    RunConfig(runId = runId, tenantId = tenantId).withTraceContext(trace)

  /** The [[org.llm4s.agent.graph.RunId RunId]]s of every attempt, the set the run-id lookup takes (`Checkpointer.latestOfRuns`). */
  def runIds: Set[RunId] = attempts.iterator.map(_.runId).toSet

@Experimental
object QueueEntry:

  /** An entry as a store read it. */
  private[llm4s] def stored(
    id: EntryId,
    threadId: ThreadId,
    agentId: AgentId,
    tenantId: Option[TenantId],
    input: QueuedInput,
    policy: BusyThreadPolicy,
    submissionKey: Option[SubmissionKey],
    maxAttempts: Int,
    trace: Option[TraceContext],
    submittedAt: Instant,
    attempts: Vector[Attempt],
    admitted: Boolean,
    leaseHolder: Option[String],
    leaseExpiresAt: Option[Instant],
    outcome: Option[EntryOutcome],
    closedAt: Option[Instant],
    closedAtSeq: Option[Long],
    position: Option[Int],
    stopRequest: Option[StopRequest]
  ): QueueEntry =
    new QueueEntry(
      id,
      threadId,
      agentId,
      tenantId,
      input,
      policy,
      submissionKey,
      maxAttempts,
      trace,
      submittedAt,
      attempts,
      admitted,
      leaseHolder,
      leaseExpiresAt,
      outcome,
      closedAt,
      closedAtSeq,
      position,
      stopRequest
    )

/**
 * A lease on an open entry, as [[RunQueue.take]] grants it: the worker holding it may record attempts at the entry,
 * renew the lease and close the entry, for as long as no other lease replaced it - expiry, by the store's clock, only
 * lets another worker take the entry over. `token` fences every call made under it. `stop` is the stop asked of the
 * entry when the store granted or last renewed the lease ([[QueueEntry.stopRequest]], W3): a worker learns of a cancel
 * or an interrupt from its renewals.
 */
@Experimental
final case class Lease(
  entryId: EntryId,
  token: LeaseToken,
  holder: String,
  expiresAt: Instant,
  stop: Option[StopRequest] = None
)

/**
 * An entry [[RunQueue.take]] leased, with its lease. `expiredLease` is whether the take replaced a lease of the entry
 * that had expired, by the store's clock, without being released or closed: its worker died, lost the store, or let the
 * lease lapse after a failed run so that the entry came back later (W2's backoff). A worker counts it as a lease expiry
 * (design 4.59); `false` for an entry never leased, or given back.
 */
@Experimental
final case class LeasedEntry(entry: QueueEntry, lease: Lease, expiredLease: Boolean = false)

/**
 * How many of an agent's open entries are waiting and leased, by the store's clock when [[RunQueue.depth]] read them:
 * `leased` hold a live lease - a worker is running or deciding them - and `waiting` do not (queued behind their thread's
 * running entry, or for a worker). `oldestWaiting` is how long the oldest waiting entry has been open since its
 * submission, by the store's clock; `None` when none waits.
 */
@Experimental
final case class QueueDepth private (waiting: Long, leased: Long, oldestWaiting: Option[FiniteDuration])

@Experimental
object QueueDepth:
  def apply(waiting: Long, leased: Long, oldestWaiting: Option[FiniteDuration]): QueueDepth =
    new QueueDepth(waiting, leased, oldestWaiting)

  /** No open entries. */
  val empty: QueueDepth = QueueDepth(0L, 0L, None)

  /** How long an entry submitted `at` has been open `now`, both by the store's clock; never negative. */
  private[agent] def age(at: Instant, now: Instant): FiniteDuration =
    scala.concurrent.duration.Duration.fromNanos(math.max(0L, java.time.Duration.between(at, now).toNanos))

/**
 * What [[RunQueue.recordAttempt]] did (Stage 5 rule 4).
 *
 *  - `Recorded`: the attempt's `RunId` is recorded, the last of `entry.attempts`; the attempt may now touch the thread,
 *    classifying it first ([[AttemptPlan]]).
 *  - `Exhausted`: the entry has made `maxAttempts` attempts, so none is recorded. The worker settles the thread if the
 *    entry's input was admitted, then closes the entry `Failed(AttemptsExhausted)` - a dead letter.
 *  - `ThreadDeleted`: the thread was deleted since the entry was submitted; the store closed the entry
 *    `Cancelled(ThreadDeleted)` and recorded nothing, so nothing runs.
 */
@Experimental
enum AttemptStart:
  case Recorded(entry: QueueEntry)
  case Exhausted(entry: QueueEntry)
  case ThreadDeleted(entry: QueueEntry)

/**
 * What [[RunQueue.take]] may take: the head entry of a thread whose agent is one of `agents`, leased to `holder` for
 * `ttl` from now, by the store's clock - passing over the entries of `skip`, which the worker has just found it cannot
 * run (W2: its agent's graph version cannot restore the thread), so they do not hide the heads behind them. A skipped
 * head still holds its own thread back (rule 5); only other threads' heads are taken past it. `skip` is bounded by
 * [[TakeRequest.MaxSkip]].
 */
@Experimental
final case class TakeRequest private (
  holder: String,
  agents: Set[AgentId],
  ttl: scala.concurrent.duration.FiniteDuration,
  skip: Set[EntryId]
):
  def withSkip(ids: Set[EntryId]): TakeRequest = TakeRequest(holder, agents, ttl, ids)

@Experimental
object TakeRequest:

  /** The most entries one take may pass over, so its query stays within the store's bound. */
  val MaxSkip: Int = 256

  def apply(holder: String, agents: Set[AgentId], ttl: scala.concurrent.duration.FiniteDuration): TakeRequest =
    apply(holder, agents, ttl, Set.empty)

  def apply(
    holder: String,
    agents: Set[AgentId],
    ttl: scala.concurrent.duration.FiniteDuration,
    skip: Set[EntryId]
  ): TakeRequest =
    require(ttl > scala.concurrent.duration.Duration.Zero, s"ttl must be positive, was $ttl")
    require(skip.size <= MaxSkip, s"skip holds ${skip.size} entries, more than $MaxSkip")
    new TakeRequest(holder, agents, ttl, skip)
