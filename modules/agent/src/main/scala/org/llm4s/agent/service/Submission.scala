package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.trace.TraceContext
import org.llm4s.types.Result

/**
 * A run to queue (design 4.55): `input` for thread `threadId`, run by the agent `agentId` for `tenantId`, treating a
 * busy thread as `policy` says, with at most `maxAttempts` attempts ([[RunQueueSettings.maxAttempts]] when built by
 * [[RunSubmitter]]). A `submissionKey` makes the submit idempotent: a second submit with the key of an entry the store
 * still holds returns that entry's ticket, and a different submission under the key is refused
 * ([[QueueError.SubmissionKeyReused]]).
 *
 * `traceContext` is the submitter's trace position (design 4.58): the entry stores it ([[QueueEntry.trace]]) and every
 * attempt runs with it as `RunConfig.traceContext` ([[QueueEntry.attemptConfig]]), so all of an entry's attempts, on
 * whatever worker or machine, sit in the submitter's trace. A retry under the same key may carry another context (a
 * new request's span): it is not part of [[sameAs]], and the entry keeps the first submission's.
 *
 * `maxOpenPerThread` and `maxInputBytes` bound what the submission may add (#3095): the store refuses it with
 * [[QueueError.ThreadQueueFull]] when its tenant already has `maxOpenPerThread` open entries on the thread, and with
 * [[QueueError.InputTooLarge]] when its input's stored JSON is longer than `maxInputBytes` bytes
 * ([[RunQueueSettings.maxOpenPerThread]] and [[RunQueueSettings.maxInputBytes]] when built by [[RunSubmitter]]).
 *
 * `apply` and the `with*` setters throw `IllegalArgumentException` for `maxAttempts`, `maxOpenPerThread` or
 * `maxInputBytes` below 1, or a [[QueuedInput.Unreadable]] input; [[Submission.of]] returns it as a `ValidationError`.
 */
@Experimental
final case class Submission private (
  threadId: ThreadId,
  agentId: AgentId,
  tenantId: Option[TenantId],
  input: QueuedInput,
  policy: BusyThreadPolicy,
  submissionKey: Option[SubmissionKey],
  maxAttempts: Int,
  traceContext: Option[TraceContext],
  maxOpenPerThread: Int,
  maxInputBytes: Long
):
  private def changed(
    threadId: ThreadId = threadId,
    agentId: AgentId = agentId,
    tenantId: Option[TenantId] = tenantId,
    input: QueuedInput = input,
    policy: BusyThreadPolicy = policy,
    submissionKey: Option[SubmissionKey] = submissionKey,
    maxAttempts: Int = maxAttempts,
    traceContext: Option[TraceContext] = traceContext,
    maxOpenPerThread: Int = maxOpenPerThread,
    maxInputBytes: Long = maxInputBytes
  ): Submission =
    Submission(
      threadId,
      agentId,
      input,
      tenantId,
      policy,
      submissionKey,
      maxAttempts,
      traceContext,
      maxOpenPerThread,
      maxInputBytes
    )

  def withThreadId(t: ThreadId): Submission                   = changed(threadId = t)
  def withAgentId(a: AgentId): Submission                     = changed(agentId = a)
  def withInput(i: QueuedInput): Submission                   = changed(input = i)
  def withTenantId(t: TenantId): Submission                   = withTenantId(Some(t))
  def withTenantId(t: Option[TenantId]): Submission           = changed(tenantId = t)
  def withPolicy(p: BusyThreadPolicy): Submission             = changed(policy = p)
  def withSubmissionKey(k: SubmissionKey): Submission         = withSubmissionKey(Some(k))
  def withSubmissionKey(k: Option[SubmissionKey]): Submission = changed(submissionKey = k)
  def withMaxAttempts(n: Int): Submission                     = changed(maxAttempts = n)
  def withTraceContext(c: TraceContext): Submission           = withTraceContext(Some(c))
  def withTraceContext(c: Option[TraceContext]): Submission   = changed(traceContext = c)
  def withMaxOpenPerThread(n: Int): Submission                = changed(maxOpenPerThread = n)
  def withMaxInputBytes(n: Long): Submission                  = changed(maxInputBytes = n)

  /**
   * Whether `other` is this submission again, as a retry under the same key would send it: everything but
   * `maxAttempts` and the bounds, which a retry may have read from changed settings, and `traceContext`, which a
   * retry's own request names afresh.
   */
  def sameAs(other: Submission): Boolean =
    threadId == other.threadId && agentId == other.agentId && tenantId == other.tenantId &&
      QueuedInput.toJson(input) == QueuedInput.toJson(other.input) && policy == other.policy &&
      submissionKey == other.submissionKey

  /** The length in bytes of the input's stored JSON, which `maxInputBytes` bounds. */
  def inputBytes: Long = Submission.bytesOf(input)

@Experimental
object Submission:

  private def problems(maxAttempts: Int, maxOpenPerThread: Int, maxInputBytes: Long, input: QueuedInput) =
    List(
      Option.when(maxAttempts < 1)(s"maxAttempts must be at least 1, was $maxAttempts"),
      Option.when(maxOpenPerThread < 1)(s"maxOpenPerThread must be at least 1, was $maxOpenPerThread"),
      Option.when(maxInputBytes < 1)(s"maxInputBytes must be at least 1, was $maxInputBytes"),
      Option.when(input.isInstanceOf[QueuedInput.Unreadable])("an unreadable input cannot be submitted")
    ).flatten

  /** The length in bytes of `input`'s stored JSON. */
  private[llm4s] def bytesOf(input: QueuedInput): Long =
    ujson.write(QueuedInput.toJson(input)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong

  /** Throws `IllegalArgumentException` for an invalid submission; use [[of]] for untrusted input. */
  def apply(
    threadId: ThreadId,
    agentId: AgentId,
    input: QueuedInput,
    tenantId: Option[TenantId] = None,
    policy: BusyThreadPolicy = BusyThreadPolicy.Reject,
    submissionKey: Option[SubmissionKey] = None,
    maxAttempts: Int = RunQueueSettings.DefaultMaxAttempts,
    traceContext: Option[TraceContext] = None,
    maxOpenPerThread: Int = RunQueueSettings.DefaultMaxOpenPerThread,
    maxInputBytes: Long = RunQueueSettings.DefaultMaxInputBytes
  ): Submission =
    val found = problems(maxAttempts, maxOpenPerThread, maxInputBytes, input)
    require(found.isEmpty, found.mkString("; "))
    new Submission(
      threadId,
      agentId,
      tenantId,
      input,
      policy,
      submissionKey,
      maxAttempts,
      traceContext,
      maxOpenPerThread,
      maxInputBytes
    )

  def of(
    threadId: ThreadId,
    agentId: AgentId,
    input: QueuedInput,
    tenantId: Option[TenantId] = None,
    policy: BusyThreadPolicy = BusyThreadPolicy.Reject,
    submissionKey: Option[SubmissionKey] = None,
    maxAttempts: Int = RunQueueSettings.DefaultMaxAttempts,
    traceContext: Option[TraceContext] = None,
    maxOpenPerThread: Int = RunQueueSettings.DefaultMaxOpenPerThread,
    maxInputBytes: Long = RunQueueSettings.DefaultMaxInputBytes
  ): Result[Submission] =
    problems(maxAttempts, maxOpenPerThread, maxInputBytes, input) match
      case Nil =>
        Right(
          new Submission(
            threadId,
            agentId,
            tenantId,
            input,
            policy,
            submissionKey,
            maxAttempts,
            traceContext,
            maxOpenPerThread,
            maxInputBytes
          )
        )
      case found => Left(ValidationError("submission", found))

  /**
   * The store's check of `submission`'s bounds (#3095), given how many open entries its tenant has on the thread:
   * [[QueueError.InputTooLarge]] or [[QueueError.ThreadQueueFull]], or nothing.
   */
  private[llm4s] def refusal(submission: Submission, open: Int): Option[QueueError] =
    val bytes = submission.inputBytes
    if bytes > submission.maxInputBytes then Some(QueueError.InputTooLarge(bytes, submission.maxInputBytes))
    else if open >= submission.maxOpenPerThread then
      Some(QueueError.ThreadQueueFull(submission.threadId, submission.maxOpenPerThread))
    else None

/**
 * What [[RunQueue.submit]] answers (design 4.55): the entry `entryId` made for the submission on `threadId`, and its
 * `position` among its tenant's open entries of the thread when the store answered (`0`: it runs next of them; another
 * tenant's entries are never counted, rule 9). `existing` is `true` when a
 * submission key named an entry the store already held, so this submit made none; its `position` is then the entry's
 * current one, and `None` once that entry has closed. `interrupted` holds the entries this submit closed - for an
 * `Interrupt` submission, the thread's earlier `Interrupt` entries that had not started, closed `Interrupted(by = this
 * entry)` in the submit's own transaction (or under its lock) - as they are after; empty otherwise, and for an
 * `existing` ticket. Only the submit that closed an entry reports it, which [[RunSubmitter]] counts (design 4.59, #2368).
 */
@Experimental
final case class Ticket(
  entryId: EntryId,
  threadId: ThreadId,
  position: Option[Int],
  existing: Boolean,
  interrupted: Vector[QueueEntry] = Vector.empty
)
