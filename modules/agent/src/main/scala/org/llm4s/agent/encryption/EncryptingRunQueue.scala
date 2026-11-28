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
 * An entry whose input cannot be opened is answered with
 * [[org.llm4s.agent.service.QueuedInput.Unreadable QueuedInput.Unreadable]] in place of its input - except an open one
 * whose key is missing, which fails the call that read it ([[EncryptionError]]). A `take` that reaches an entry whose
 * ciphertext cannot be opened with the key it names - altered, malformed, or not
 * encrypted at all - closes it `Failed(Unrunnable)` and takes the next one, as a store does with an entry it cannot
 * decode (#3091). An entry whose key is missing ([[EncryptionError.UnknownKey]], [[EncryptionError.InvalidKey]],
 * [[EncryptionError.NoCurrentKey]]) is a configuration problem a worker cannot fix by failing the entry: its take
 * fails, and the entry stays at the head of its thread's queue, leased again as each lease expires, until its key is
 * supplied or the entry is cancelled.
 */
@Experimental
final class EncryptingRunQueue private (val inner: RunQueue, keys: EncryptionKeySource, settings: EncryptionSettings)
    extends RunQueue:
  import PayloadCipher.{ traverse, Codec }

  private def cipher(threadId: ThreadId) = PayloadCipher.ofThread(threadId, keys, settings)

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
      case QueuedInput.Recover                => Right(QueuedInput.Recover)
      case unreadable: QueuedInput.Unreadable => Right(unreadable)

  /**
   * `entry` with its input opened; one whose ciphertext cannot be opened, or a closed one whose key is missing, is
   * answered without it (#3091).
   */
  private def opened(entry: QueueEntry): Result[QueueEntry] =
    sealedOpen(entry).left.flatMap {
      case error: EncryptionError if !entry.isOpen || unopenable(error) =>
        Right(withInput(entry, QueuedInput.Unreadable(s"its input cannot be opened: ${error.message}")))
      case other => Left(other)
    }

  private def sealedOpen(entry: QueueEntry): Result[QueueEntry] =
    through(cipher(entry.threadId).open, entry.tenantId, entry.agentId, entry.input).map(withInput(entry, _))

  private def withInput(entry: QueueEntry, input: QueuedInput): QueueEntry =
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

  /**
   * Leases the next entry, with its input decrypted. An entry whose ciphertext cannot be opened is closed
   * `Failed(Unrunnable)` and the next one taken; one whose key is missing is a `Left`, and stays leased until the lease
   * expires (see the class doc).
   */
  def take(request: TakeRequest): Result[Option[LeasedEntry]] =
    @scala.annotation.tailrec
    def next(closed: Int): Result[Option[LeasedEntry]] =
      inner.take(request) match
        case Right(Some(leased)) =>
          sealedOpen(leased.entry) match
            case Right(entry) => Right(Some(leased.copy(entry = entry)))
            case Left(error: EncryptionError) if unopenable(error) && closed < EncryptingRunQueue.MaxClosedPerTake =>
              val outcome =
                EntryOutcome.Failed(
                  FailureReason.Unrunnable(
                    s"the stored entry cannot be read: its input cannot be opened: ${error.message}"
                  )
                )
              inner.close(leased.lease, outcome) match
                case Right(_) | Left(_: QueueError) => next(closed + 1)
                case Left(other)                    => Left(other)
            case Left(error) => Left(error)
        case other => other
    next(0)

  /** Whether `error` says the ciphertext itself cannot be opened, rather than that its key is missing. */
  private def unopenable(error: EncryptionError): Boolean = error match
    case _: EncryptionError.DecryptionFailed | _: EncryptionError.MalformedCiphertext |
        _: EncryptionError.NotEncrypted =>
      true
    case _ => false

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
  def allDepths(): Result[Map[AgentId, QueueDepth]] = inner.allDepths()

  /** Passed to the store; the entries it closed are answered with their inputs opened, where they can be. */
  def closeUntaken(age: FiniteDuration, limit: Int): Result[Vector[QueueEntry]] =
    inner.closeUntaken(age, limit).flatMap(traverse(_)(opened))

  /** Passed to the store unchanged. */
  def renew(lease: Lease, ttl: FiniteDuration): Result[Lease] = inner.renew(lease, ttl)

  /** Passed to the store unchanged. */
  def release(lease: Lease): Result[Unit]                                    = inner.release(lease)
  override def releaseDrained(lease: Lease): Result[Unit]                    = inner.releaseDrained(lease)
  def reclaim(ticketLifetime: FiniteDuration, limit: Int): Result[Int]       = inner.reclaim(ticketLifetime, limit)
  def openRuns(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]] = inner.openRuns(threads)

@Experimental
object EncryptingRunQueue:

  /** The most entries one take closes for an input that cannot be opened before it answers none, for its bound. */
  private val MaxClosedPerTake: Int = 16

  /** `inner`, storing its entries' inputs encrypted with `keys`. */
  def apply(
    inner: RunQueue,
    keys: EncryptionKeySource,
    settings: EncryptionSettings = EncryptionSettings()
  ): EncryptingRunQueue = new EncryptingRunQueue(inner, keys, settings)
