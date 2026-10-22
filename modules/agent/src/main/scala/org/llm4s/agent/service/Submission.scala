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
 * `apply` and the `with*` setters throw `IllegalArgumentException` for `maxAttempts` below 1; [[Submission.of]]
 * returns it as a `ValidationError`.
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
  traceContext: Option[TraceContext]
):
  def withTenantId(t: TenantId): Submission = withTenantId(Some(t))
  def withTenantId(t: Option[TenantId]): Submission =
    Submission(threadId, agentId, input, t, policy, submissionKey, maxAttempts, traceContext)
  def withPolicy(p: BusyThreadPolicy): Submission =
    Submission(threadId, agentId, input, tenantId, p, submissionKey, maxAttempts, traceContext)
  def withSubmissionKey(k: SubmissionKey): Submission = withSubmissionKey(Some(k))
  def withSubmissionKey(k: Option[SubmissionKey]): Submission =
    Submission(threadId, agentId, input, tenantId, policy, k, maxAttempts, traceContext)
  def withMaxAttempts(n: Int): Submission =
    Submission(threadId, agentId, input, tenantId, policy, submissionKey, n, traceContext)
  def withTraceContext(c: TraceContext): Submission = withTraceContext(Some(c))
  def withTraceContext(c: Option[TraceContext]): Submission =
    Submission(threadId, agentId, input, tenantId, policy, submissionKey, maxAttempts, c)

  /**
   * Whether `other` is this submission again, as a retry under the same key would send it: everything but
   * `maxAttempts`, which a retry may have read from changed settings, and `traceContext`, which a retry's own request
   * names afresh.
   */
  def sameAs(other: Submission): Boolean =
    threadId == other.threadId && agentId == other.agentId && tenantId == other.tenantId &&
      QueuedInput.toJson(input) == QueuedInput.toJson(other.input) && policy == other.policy &&
      submissionKey == other.submissionKey

@Experimental
object Submission:

  private def problems(maxAttempts: Int): List[String] =
    Option.when(maxAttempts < 1)(s"maxAttempts must be at least 1, was $maxAttempts").toList

  /** Throws `IllegalArgumentException` for `maxAttempts` below 1; use [[of]] for untrusted input. */
  def apply(
    threadId: ThreadId,
    agentId: AgentId,
    input: QueuedInput,
    tenantId: Option[TenantId] = None,
    policy: BusyThreadPolicy = BusyThreadPolicy.Reject,
    submissionKey: Option[SubmissionKey] = None,
    maxAttempts: Int = RunQueueSettings.DefaultMaxAttempts,
    traceContext: Option[TraceContext] = None
  ): Submission =
    val found = problems(maxAttempts)
    require(found.isEmpty, found.mkString("; "))
    new Submission(threadId, agentId, tenantId, input, policy, submissionKey, maxAttempts, traceContext)

  def of(
    threadId: ThreadId,
    agentId: AgentId,
    input: QueuedInput,
    tenantId: Option[TenantId] = None,
    policy: BusyThreadPolicy = BusyThreadPolicy.Reject,
    submissionKey: Option[SubmissionKey] = None,
    maxAttempts: Int = RunQueueSettings.DefaultMaxAttempts,
    traceContext: Option[TraceContext] = None
  ): Result[Submission] =
    problems(maxAttempts) match
      case Nil =>
        Right(new Submission(threadId, agentId, tenantId, input, policy, submissionKey, maxAttempts, traceContext))
      case found => Left(ValidationError("submission", found))

/**
 * What [[RunQueue.submit]] answers (design 4.55): the entry `entryId` made for the submission on `threadId`, and its
 * `position` among the thread's open entries when the store answered (`0`: it runs next). `existing` is `true` when a
 * submission key named an entry the store already held, so this submit made none; its `position` is then the entry's
 * current one, and `None` once that entry has closed.
 */
@Experimental
final case class Ticket(entryId: EntryId, threadId: ThreadId, position: Option[Int], existing: Boolean)
