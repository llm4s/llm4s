package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ CheckpointStatus, RunId, TenantId, ThreadId }
import org.llm4s.annotation.Experimental

import java.time.Instant
import scala.util.Try

/**
 * How a submission treats a thread that already has an open entry or a live run (design 4.55; W3 #2136 gives each its
 * full behaviour). `Reject`, the default, refuses the submission with `GraphError.ThreadBusy`, as a direct run on a
 * busy thread is refused (§5.3). `Enqueue` waits behind the thread's open entries, in submission order. `Interrupt`
 * goes ahead of the thread's queued entries, which keep their order behind it; of two `Interrupt` entries the later
 * goes first and the earlier is closed [[EntryOutcome.Interrupted]] before it runs (Stage 5 rule 5). Stopping the
 * thread's running entry at its next safe point is W3's.
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
 * submitted, so the entry ran nothing (a deleted thread is never resurrected, rule 4). `Requested`: a cancel request
 * stopped it (W3), with the requester's note.
 */
@Experimental
enum CancelReason:
  case ThreadDeleted
  case Requested(note: Option[String])

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
 * @param attempts the attempts recorded, oldest first, each before it touched the thread
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
  submittedAt: Instant,
  attempts: Vector[Attempt],
  leaseHolder: Option[String],
  leaseExpiresAt: Option[Instant],
  outcome: Option[EntryOutcome],
  closedAt: Option[Instant],
  position: Option[Int]
):
  /** Whether the entry is still to run or running: it has no outcome. */
  def isOpen: Boolean = outcome.isEmpty

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
    submittedAt: Instant,
    attempts: Vector[Attempt],
    leaseHolder: Option[String],
    leaseExpiresAt: Option[Instant],
    outcome: Option[EntryOutcome],
    closedAt: Option[Instant],
    position: Option[Int]
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
      submittedAt,
      attempts,
      leaseHolder,
      leaseExpiresAt,
      outcome,
      closedAt,
      position
    )

  /** Attempts as an entry stores them: `[{"runId", "startedAt", "holder"}, ...]`. */
  private[llm4s] def attemptsToJson(attempts: Vector[Attempt]): ujson.Value =
    ujson.Arr.from(attempts.map { a =>
      ujson.Obj("runId" -> a.runId.value, "startedAt" -> a.startedAt.toString, "holder" -> a.holder)
    })

  /** The attempts [[attemptsToJson]] stored, or why they cannot be read. */
  private[llm4s] def attemptsFromJson(json: ujson.Value): Either[String, Vector[Attempt]] =
    Try(json.arr.toVector.map { a =>
      Attempt(RunId(a("runId").str), Instant.parse(a("startedAt").str), a("holder").str)
    }).toEither.left.map(e => s"not an attempt list: ${e.getMessage}")

/**
 * A lease on an open entry, as [[RunQueue.take]] grants it: the worker holding it may record attempts at the entry,
 * renew the lease and close the entry, for as long as no other lease replaced it - expiry, by the store's clock, only
 * lets another worker take the entry over. `token` fences every call made under it.
 */
@Experimental
final case class Lease(entryId: EntryId, token: LeaseToken, holder: String, expiresAt: Instant)

/** An entry [[RunQueue.take]] leased, with its lease. */
@Experimental
final case class LeasedEntry(entry: QueueEntry, lease: Lease)

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
 * `ttl` from now, by the store's clock.
 */
@Experimental
final case class TakeRequest private (
  holder: String,
  agents: Set[AgentId],
  ttl: scala.concurrent.duration.FiniteDuration
)

@Experimental
object TakeRequest:
  def apply(holder: String, agents: Set[AgentId], ttl: scala.concurrent.duration.FiniteDuration): TakeRequest =
    require(ttl > scala.concurrent.duration.Duration.Zero, s"ttl must be positive, was $ttl")
    new TakeRequest(holder, agents, ttl)
