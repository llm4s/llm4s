package org.llm4s.agent.encryption

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ RunId, TenantId, ThreadId }
import org.llm4s.agent.service.*
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * A [[org.llm4s.agent.service.RunQueue RunQueue]] that encrypts the inputs `inner` stores, with keys from `keys`
 * (#2449): the companion of [[EncryptingCheckpointer]] for a store's queue, which is a separate SPI and so is not
 * covered by wrapping the checkpointer. Give it to every `RunSubmitter` and `AgentWorker` in place of the store's
 * queue.
 *
 * '''What is encrypted''': a `Start` input and each answer of a `Resume`, under the key of the submission's tenant,
 * bound to the tenant, the thread, the agent and - for an answer - its interrupt. The store issues an entry's id as it
 * inserts it, so an input is not bound to its entry: someone who can write to the database can exchange the inputs of
 * two entries of one thread, tenant and agent, and nothing else.
 *
 * '''What stays in plain''': everything the queue orders, leases, scopes and reports by - entry, thread, agent, tenant
 * and run ids, the submission key, the policy, attempts and lease holders, timestamps, trace contexts, an input's kind
 * and its interrupt ids - and an entry's outcome, with the failure message or cancel note it carries: `close` and
 * `cancel` name an entry by its lease or id alone, without the tenant a key is chosen by.
 *
 * A retried submission under a [[org.llm4s.agent.service.SubmissionKey SubmissionKey]] still gets the first one's
 * ticket. The store compares what it holds, and two encryptions of one input differ, so it reports the key as reused;
 * this queue then reads that entry, opens it and compares the submissions itself.
 *
 * An entry whose input cannot be opened fails the call that read it ([[EncryptionError]]) - after `inner` has acted:
 * a `take` has leased it, a `cancel` has cancelled it. Such an entry stays at the head of its thread's queue, leased
 * again as each lease expires, until its key is supplied or the entry is cancelled.
 */
@Experimental
final class EncryptingRunQueue private (val inner: RunQueue, keys: EncryptionKeySource, settings: EncryptionSettings)
    extends RunQueue:
  import ThreadCipher.{ traverse, Codec }

  private def cipher(threadId: ThreadId) = new ThreadCipher(threadId, keys, settings)

  private def through(
    codec: Codec,
    tenantId: Option[TenantId],
    agentId: AgentId,
    input: QueuedInput
  ): Result[QueuedInput] =
    val place = Vector("queue") ++ tenantId.fold(Vector("-", ""))(t => Vector("t", t.value)) :+ agentId.value
    input match
      case QueuedInput.Start(value) => codec(place :+ "start", value).map(QueuedInput.Start(_))
      case QueuedInput.Resume(answers) =>
        traverse(answers.toVector)((id, answer) => codec(place ++ Vector("resume", id.value), answer).map(id -> _))
          .map(opened => QueuedInput.Resume(opened.toMap))
      case QueuedInput.Recover => Right(QueuedInput.Recover)

  private def opened(entry: QueueEntry): Result[QueueEntry] =
    through(cipher(entry.threadId).open, entry.tenantId, entry.agentId, entry.input).map { input =>
      QueueEntry.stored(
        entry.id,
        entry.threadId,
        entry.agentId,
        entry.tenantId,
        input,
        entry.policy,
        entry.submissionKey,
        entry.maxAttempts,
        entry.trace,
        entry.submittedAt,
        entry.attempts,
        entry.admitted,
        entry.leaseHolder,
        entry.leaseExpiresAt,
        entry.outcome,
        entry.closedAt,
        entry.closedAtSeq,
        entry.position,
        entry.stopRequest
      )
    }

  /** Submits `submission` with its input encrypted; a reused submission key is compared against the stored entry in plain. */
  def submit(submission: Submission): Result[Ticket] =
    val thread = cipher(submission.threadId)
    // a recover holds nothing to seal, so it asks for no key
    val writing: Result[Option[EncryptionKey]] = submission.input match
      case QueuedInput.Recover => Right(None)
      case _                   => keys.current(submission.tenantId).map(Some(_))
    for
      key <- writing
      seal = (place: Vector[String], value: ujson.Value) =>
        key.fold[Result[ujson.Value]](Right(value))(thread.seal(_, place, value))
      input       <- through(seal, submission.tenantId, submission.agentId, submission.input)
      ticket      <- submitted(submission, input)
      interrupted <- traverse(ticket.interrupted)(opened)
    yield ticket.copy(interrupted = interrupted)

  private def submitted(submission: Submission, input: QueuedInput): Result[Ticket] =
    inner.submit(submission.withInput(input)) match
      case Left(reused: QueueError.SubmissionKeyReused) => resubmitted(submission, reused)
      case other                                        => other

  /** The ticket of the entry the store says `submission`'s key names, if that entry is this submission. */
  private def resubmitted(submission: Submission, reused: QueueError.SubmissionKeyReused): Result[Ticket] =
    inner.entry(submission.tenantId, reused.entryId).flatMap(opened) match
      case Right(held) if same(held, submission) =>
        Right(Ticket(held.id, held.threadId, held.position, existing = true))
      case Right(_) => Left(reused)
      // the entry cannot be opened: that, not a reused key, is what is wrong
      case Left(error: EncryptionError) => Left(error)
      case Left(_)                      => Left(reused)

  /** `Submission.sameAs`, against the entry a submission made. */
  private def same(held: QueueEntry, submission: Submission): Boolean =
    held.threadId == submission.threadId && held.agentId == submission.agentId &&
      held.tenantId == submission.tenantId && held.policy == submission.policy &&
      held.submissionKey == submission.submissionKey &&
      QueuedInput.toJson(held.input) == QueuedInput.toJson(submission.input)

  /** The entry, with its input decrypted. */
  def entry(tenantId: Option[TenantId], entryId: EntryId): Result[QueueEntry] =
    inner.entry(tenantId, entryId).flatMap(opened)

  override def awaitClosed(tenantId: Option[TenantId], entryId: EntryId, timeout: FiniteDuration): Result[QueueEntry] =
    inner.awaitClosed(tenantId, entryId, timeout).flatMap(opened)

  def openEntries(tenantId: Option[TenantId], threadId: ThreadId): Result[Vector[QueueEntry]] =
    inner.openEntries(tenantId, threadId).flatMap(traverse(_)(opened))

  /** Leases the next entry, with its input decrypted; an entry that cannot be decrypted is a `Left` and stays leased until the lease expires. */
  def take(request: TakeRequest): Result[Option[LeasedEntry]] =
    inner.take(request).flatMap {
      case None         => Right(None)
      case Some(leased) => opened(leased.entry).map(entry => Some(leased.copy(entry = entry)))
    }

  /** Passed to the store unchanged: a cancellation carries nothing that is encrypted. */
  def cancel(tenantId: Option[TenantId], target: CancelTarget, reason: CancelReason): Result[Cancellation] =
    inner.cancel(tenantId, target, reason).flatMap(c => traverse(c.entries)(opened).map(Cancellation(_, c.closed)))

  /** Passed to the store unchanged: an attempt record carries nothing that is encrypted. */
  def recordAttempt(lease: Lease, runId: RunId): Result[AttemptStart] =
    inner.recordAttempt(lease, runId).flatMap {
      case AttemptStart.Recorded(entry)      => opened(entry).map(AttemptStart.Recorded(_))
      case AttemptStart.Exhausted(entry)     => opened(entry).map(AttemptStart.Exhausted(_))
      case AttemptStart.ThreadDeleted(entry) => opened(entry).map(AttemptStart.ThreadDeleted(_))
    }

  def close(lease: Lease, outcome: EntryOutcome): Result[QueueEntry] = inner.close(lease, outcome).flatMap(opened)

  // ---- forwarded as they are: nothing they carry is encrypted ----

  /** Passed to the store unchanged. */
  def depth(agents: Set[AgentId]): Result[Map[AgentId, QueueDepth]] = inner.depth(agents)

  /** Passed to the store unchanged. */
  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease] = inner.renew(lease, ttl)

  /** Passed to the store unchanged. */
  def release(lease: Lease): Result[Unit]                                    = inner.release(lease)
  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int]       = inner.reclaim(ticketLifetime, limit)
  def openRuns(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]] = inner.openRuns(threads)

@Experimental
object EncryptingRunQueue:

  /** `inner`, storing its entries' inputs encrypted with `keys`. */
  def apply(
    inner: RunQueue,
    keys: EncryptionKeySource,
    settings: EncryptionSettings = EncryptionSettings()
  ): EncryptingRunQueue = new EncryptingRunQueue(inner, keys, settings)
