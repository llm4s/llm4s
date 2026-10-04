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
  final case class ToolFailed(tool: String, toolCallId: String, cause: LLMError)
      extends GraphError
      with NonRecoverableError:
    override val message: String = s"Tool '$tool' (call $toolCallId) failed: ${cause.message}"

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

  /** Events before `earliestSeq` were compacted away and cannot be replayed. */
  final case class ReplayUnavailable(threadId: String, earliestSeq: Long) extends GraphError with NonRecoverableError:
    override val message: String = s"Thread '$threadId' can replay events from sequence $earliestSeq only"

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
   * Another run holds the thread: it is still executing in this runtime, or it claimed the thread
   * between this call reading it and claiming it. Nothing was accepted or discarded - retry once
   * that run has suspended or completed.
   */
  final case class ThreadBusy(threadId: String, latestCheckpoint: Option[String])
      extends GraphError
      with NonRecoverableError:
    override val message: String =
      s"Thread '$threadId' is held by another run (now at ${latestCheckpoint.getOrElse("<none>")}); retry"

  /**
   * An unexpected throwable - such as a `Clock` that threw - refused the run at admission, or
   * ended it where it was; a checkpointer whose commit throws is reported as
   * [[CheckpointWriteFailed]] instead. Its thread claim is released; whatever the
   * store holds for the thread stands.
   */
  final case class RunCrashed(threadId: String, cause: Throwable) extends GraphError with NonRecoverableError:
    override val message: String = s"Run on thread '$threadId' crashed: $cause"
