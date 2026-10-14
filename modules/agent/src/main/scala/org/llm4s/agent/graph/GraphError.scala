package org.llm4s.agent.graph

import org.llm4s.error.{ LLMError, NonRecoverableError, RecoverableError }

/**
 * Errors raised while building, running or restoring a typed graph. Every case is a
 * [[NonRecoverableError]] except [[GraphError.DeadlineExceeded]]: `recover` with a new budget
 * continues that run.
 */
sealed trait GraphError extends LLMError

object GraphError:

  /** The builder found one or more structural problems; every problem is listed. */
  final case class InvalidGraph(graphId: String, problems: List[String]) extends GraphError with NonRecoverableError:
    override val message: String = s"Graph '$graphId' is invalid: ${problems.mkString("; ")}"

  /** A key was read or written that the graph did not register, or another key shares its id. */
  final case class UnknownStateKey(keyId: StateKeyId) extends GraphError with NonRecoverableError:
    override val message: String = s"State key '${keyId.value}' is not registered with this graph"

  /** A node emitted an update for a key outside its declared write set. */
  final case class UndeclaredWrite(nodeId: NodeId, taskId: TaskId, keyId: StateKeyId)
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Node '${nodeId.value}' (task ${taskId.value}) wrote '${keyId.value}', which it does not declare"

  /** A node returned a route this graph cannot schedule. */
  final case class InvalidRoute(nodeId: NodeId, taskId: TaskId, reason: String)
      extends GraphError
      with NonRecoverableError:
    override val message: String = s"Node '${nodeId.value}' (task ${taskId.value}) returned an invalid route: $reason"

  /** A key's update function rejected an update. */
  final case class StateUpdateFailed(keyId: StateKeyId, cause: LLMError) extends GraphError with NonRecoverableError:
    override val message: String = s"Update to state key '${keyId.value}' failed: ${cause.message}"

  /** A node returned a failure or threw. */
  final case class NodeFailed(nodeId: NodeId, taskId: TaskId, cause: LLMError)
      extends GraphError
      with NonRecoverableError:
    override val message: String = s"Node '${nodeId.value}' (task ${taskId.value}) failed: ${cause.message}"

  /**
   * A tool call failed the run: the tool returned `Fatal`, or broke its contract (an update to a key
   * it does not declare, a question it does not declare). `cause` says which.
   */
  final case class ToolFailed private (tool: ToolName, toolCallId: ToolCallId, cause: LLMError)
      extends GraphError
      with NonRecoverableError:
    override val message: String          = s"Tool '${tool.value}' (call ${toolCallId.value}) failed: ${cause.message}"
    def withTool(t: ToolName): ToolFailed = copy(tool = t)
    def withToolCallId(id: ToolCallId): ToolFailed = copy(toolCallId = id)
    def withCause(c: LLMError): ToolFailed         = copy(cause = c)

  object ToolFailed:
    def apply(tool: ToolName, toolCallId: ToolCallId, cause: LLMError): ToolFailed =
      new ToolFailed(tool, toolCallId, cause)

  /**
   * A middleware hook threw: the run fails, the checkpoint stays `Running`, and `recover` re-runs
   * only that task. `middleware` is the middleware's id.
   */
  final case class MiddlewareFailed(middleware: String, cause: Throwable) extends GraphError with NonRecoverableError:
    override val message: String = s"Middleware '$middleware' failed: ${describe(cause)}"

  /**
   * An agent's answer did not match its response format (`AgentBuilder.withOutput`, design 4.26) after one
   * corrective retry: the turn ended as a Block, removed from the thread, with both model calls in its usage.
   * `errors` are the second answer's problems, each prefixed with a JSON path - at most 20, each at most 300
   * characters. They quote the answer, so `message`, which durable events record, gives only their count.
   */
  final case class OutputInvalid(errors: Vector[String]) extends GraphError with NonRecoverableError:
    private val problems = if errors.size == 1 then "1 problem" else s"${errors.size} problems"
    override val message: String =
      s"The agent's answer did not match its response format after a corrective retry ($problems)"

  private def describe(thrown: Throwable): String = Option(thrown.getMessage).getOrElse(thrown.toString)

  /** The graph went quiescent while a join still waited for arrivals that nothing can produce. */
  final case class UnsatisfiedJoin(joinId: JoinId, missing: List[String]) extends GraphError with NonRecoverableError:
    override val message: String =
      s"Join '${joinId.value}' can never release; still waiting for ${missing.mkString(", ")}"

  /**
   * The run was cancelled by interrupting its thread - [[RunHandle.cancel]] in a runtime, or
   * interrupting the thread driving [[CompiledGraph.step]]. Every task was interrupted and joined.
   * In a runtime, tasks that finished first keep their results as pending writes, and the
   * checkpoint stays `Running`, so `recover` continues it without re-running them. A step keeps
   * nothing from the cancelled superstep, and returns this with the interrupt flag set.
   */
  final case class Cancelled(threadId: Option[String], lastCheckpoint: Option[String])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Run${threadId.fold("")(t => s" on thread '$t'")} was cancelled${lastCheckpoint.fold("")(c => s"; recover from $c")}"

  /**
   * The run's `RunBudgets.timeout`, measured from its claim, expired: the run was stopped exactly as
   * by [[Cancelled]], and a [[RunEvent.RunTimedOut]] event was committed against `lastCheckpoint`.
   * The checkpoint stays `Running`, so `recover` with a new budget continues the run without
   * re-running tasks that had finished.
   */
  final case class DeadlineExceeded(threadId: String, lastCheckpoint: Option[String])
      extends GraphError
      with RecoverableError:
    override val message: String =
      s"Run on thread '$threadId' exceeded its deadline${lastCheckpoint.fold("")(c => s"; recover from $c")}"

  /** The run reached its superstep limit with work still scheduled. */
  final case class SuperstepLimitExceeded(limit: Int) extends GraphError with NonRecoverableError:
    override val message: String = s"Graph exceeded its limit of $limit supersteps"

  /** An execution was passed to a graph other than the one that created or restored it. */
  final case class ForeignExecution(graphId: String) extends GraphError with NonRecoverableError:
    override val message: String = s"Execution does not belong to graph '$graphId'"

  /** A snapshot does not match this compiled graph; every problem is listed. */
  final case class RestoreRejected(graphId: String, problems: List[String]) extends GraphError with NonRecoverableError:
    override val message: String = s"Snapshot cannot be restored into graph '$graphId': ${problems.mkString("; ")}"

  /** A checkpoint was written in a format this build does not read. */
  final case class UnsupportedCheckpointFormat(found: Int, supported: Int) extends GraphError with NonRecoverableError:
    override val message: String = s"Checkpoint format $found is not supported; this build reads up to $supported"

  /** A new checkpoint's parent is not the thread's latest: another writer advanced the thread. */
  final case class CheckpointConflict(threadId: String, expected: Option[String], actual: Option[String])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' is at checkpoint ${actual.getOrElse("<none>")}, not ${expected.getOrElse("<none>")}"

  /** A commit is malformed, such as a pending write naming a checkpoint other than the latest. */
  final case class InvalidCommit(threadId: String, reason: String) extends GraphError with NonRecoverableError:
    override val message: String = s"Invalid commit to thread '$threadId': $reason"

  /**
   * The thread has no checkpoint `checkpointId` in its history: it never had one, or retention
   * ([[Checkpointer.prune]]), a guardrail `Block` (which retracts its turn) or `deleteThread` removed it.
   * Nothing was changed.
   */
  final case class CheckpointNotFound(threadId: String, checkpointId: String)
      extends GraphError
      with NonRecoverableError:
    override val message: String = s"Thread '$threadId' has no checkpoint $checkpointId"

  /** A fork's target thread already exists; a fork only ever creates a new thread. Nothing was changed. */
  final case class ThreadExists(threadId: String) extends GraphError with NonRecoverableError:
    override val message: String = s"Thread '$threadId' already exists; fork to a new thread id"

  /** Events before `earliestSeq` were compacted away and cannot be replayed. */
  final case class ReplayUnavailable(threadId: String, earliestSeq: Long) extends GraphError with NonRecoverableError:
    override val message: String = s"Thread '$threadId' can replay events from sequence $earliestSeq only"

  /**
   * A read of the thread's event log after `afterSeq` was refused because that point is in the log of a
   * thread of the same id that [[Checkpointer.deleteThread]] removed ([[Checkpointer.deleted]]): the
   * thread now under the id - if one has been created - numbers its events from `firstSeq`, so its log
   * is not a continuation of the one the reader was following. A subscription ends on it with
   * [[DisconnectReason.ThreadDeleted]]. Read from `0` to follow the thread now under the id.
   */
  final case class ThreadDeleted(threadId: String, afterSeq: Long, firstSeq: Long)
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' was deleted after event $afterSeq; the thread now under its id starts at event $firstSeq"

  /** `start` was called on a thread whose latest checkpoint is mid-execution; use `recover`. */
  final case class IncompleteRun(threadId: String, checkpointId: String) extends GraphError with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' has an incomplete execution at checkpoint $checkpointId; recover it before starting a new run"

  /** `recover` was called on a thread with no incomplete execution. */
  final case class NothingToRecover(threadId: String) extends GraphError with NonRecoverableError:
    override val message: String = s"Thread '$threadId' has no incomplete execution to recover"

  /**
   * The checkpointer refused or failed a commit; the run stops at its last durable checkpoint.
   * `runError` is the run's own failure when the commit failed while recording it.
   */
  final case class CheckpointWriteFailed(threadId: String, cause: LLMError, runError: Option[LLMError] = None)
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Checkpoint write for thread '$threadId' failed: ${cause.message}" +
        runError.fold("")(e => s" (while recording the run's failure: ${e.message})")

  /** A resume was refused: no answers, an interrupt that is not parked, or an answer that does not decode. */
  final case class InvalidResume(graphId: String, problems: List[String]) extends GraphError with NonRecoverableError:
    override val message: String = s"Cannot resume graph '$graphId': ${problems.mkString("; ")}"

  /** `start` or `recover` was called on a suspended thread; answer its interrupts with `resume`. */
  final case class PendingInterrupts(threadId: String, interrupts: List[String])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' is suspended on ${interrupts.mkString(", ")}; resume it with answers"

  /**
   * The run's tenant is not the thread's: a thread belongs to the tenant recorded on its latest
   * checkpoint, and `requested` (the caller's `RunConfig.tenantId`) is not it. `None` and `Some`
   * differ. Nothing was changed. The owning tenant is deliberately not carried or printed, so a
   * caller from another tenant learns nothing about the thread.
   */
  final case class TenantMismatch(threadId: String, requested: Option[String])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' does not belong to tenant ${requested.fold("<none>")(t => s"'$t'")}"

  /** `resume` was called on a thread that is not suspended. */
  final case class NotSuspended(threadId: String) extends GraphError with NonRecoverableError:
    override val message: String = s"Thread '$threadId' has no suspended run to resume"

  /**
   * Another run holds the thread: it is still executing in this runtime, another runtime or process
   * holds a live claim on it ([[RunClaim]]), or it claimed the thread between this call reading it
   * and claiming it. Nothing was accepted or discarded - retry once that run has suspended or
   * completed, or once its claim has expired. `holder` is the run id of the claim's holder when it
   * is known and the caller may see it: it is never named for a thread that has no checkpoint yet in
   * the store, whose tenant cannot be checked.
   */
  final case class ThreadBusy(threadId: String, latestCheckpoint: Option[String], holder: Option[String] = None)
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' is held by ${holder.fold("another run")(h => s"run '$h'")} (now at ${latestCheckpoint
          .getOrElse("<none>")}); retry"

  /**
   * A commit, renewal or release presented a fencing token that is not the thread's current claim:
   * the claim was taken over after it expired, was released, or never existed. A
   * [[Checkpointer]] refuses such a commit atomically, applying nothing and numbering no event, so a
   * writer that lost its claim cannot record anything after another run took the thread over.
   * `current` is the token of the thread's current claim, if there is one.
   */
  final case class StaleClaim(threadId: String, token: Long, current: Option[Long])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Claim token $token on thread '$threadId' is stale (current: ${current.fold("none")(_.toString)})"

  /**
   * The run lost its claim on the thread, so it stopped: its lease passed with no renewal landing (the
   * run judges this itself, by its own monotonic clock), a renewal was refused ([[StaleClaim]]), or the
   * store refused one of its commits as stale. The run commits nothing more - not even a `RunFailed` -
   * and drops what it had not yet written; whatever it made durable before stands. `token` is the run's
   * own claim, and `current` the thread's current claim when the store named one: another run then
   * holds the thread. `None` means the store named none - the run judged its lease passed without
   * asking it, or the store found no current claim (a successor that took over has since released
   * it). A failure the run was recording when its commit was refused is logged, not carried. Unlike
   * [[CheckpointWriteFailed]], it is not a store outage. `recover` may be attempted once the thread is
   * free: it is refused with [[ThreadBusy]] while a successor holds the thread, so retry; a successor
   * that finished the work leaves the thread's state showing it ([[NothingToRecover]]); and a lease that
   * lapsed with no successor leaves the last checkpoint `Running`, which `recover` continues.
   */
  final case class ClaimLost(threadId: String, token: FencingToken, current: Option[FencingToken])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Run on thread '$threadId' lost its claim (token ${token.value}, current: ${current
          .fold("not named")(_.value.toString)}); it stopped and committed nothing more"

  /**
   * An unexpected throwable - such as a `Clock` that threw - refused the run at admission, or
   * ended it where it was; a checkpointer whose commit throws is reported as
   * [[CheckpointWriteFailed]] instead. Its thread claim is released; whatever the
   * store holds for the thread stands.
   */
  final case class RunCrashed(threadId: String, cause: Throwable) extends GraphError with NonRecoverableError:
    override val message: String = s"Run on thread '$threadId' crashed: $cause"

  /**
   * [[ReplayGuard]] did not perform or replay effect `operation`, for `reason`: its target changed after
   * the effect began, it may already have run and cannot be checked, or its outcome was not kept. A
   * refusal the model should see: a tool maps it to `ToolOutcome.Error` (design §4.24).
   */
  final case class EffectReplayRefused(operation: String, reason: String) extends GraphError with NonRecoverableError:
    override val message: String = s"The effect '$operation' was not replayed: $reason"
