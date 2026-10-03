package org.llm4s.agent.graph

import org.llm4s.error.{ LLMError, NonRecoverableError }

/** Errors raised while building, running or restoring a typed graph. */
sealed trait GraphError extends NonRecoverableError

object GraphError:

  /** The builder found one or more structural problems; every problem is listed. */
  final case class InvalidGraph(graphId: String, problems: List[String]) extends GraphError:
    override val message: String = s"Graph '$graphId' is invalid: ${problems.mkString("; ")}"

  /** A key was read or written that the graph did not register, or another key shares its id. */
  final case class UnknownStateKey(keyId: StateKeyId) extends GraphError:
    override val message: String = s"State key '${keyId.value}' is not registered with this graph"

  /** A node emitted an update for a key outside its declared write set. */
  final case class UndeclaredWrite(nodeId: NodeId, taskId: TaskId, keyId: StateKeyId) extends GraphError:
    override val message: String =
      s"Node '${nodeId.value}' (task ${taskId.value}) wrote '${keyId.value}', which it does not declare"

  /** A node returned a route this graph cannot schedule. */
  final case class InvalidRoute(nodeId: NodeId, taskId: TaskId, reason: String) extends GraphError:
    override val message: String = s"Node '${nodeId.value}' (task ${taskId.value}) returned an invalid route: $reason"

  /** A key's update function rejected an update. */
  final case class StateUpdateFailed(keyId: StateKeyId, cause: LLMError) extends GraphError:
    override val message: String = s"Update to state key '${keyId.value}' failed: ${cause.message}"

  /** A node returned a failure or threw. */
  final case class NodeFailed(nodeId: NodeId, taskId: TaskId, cause: LLMError) extends GraphError:
    override val message: String = s"Node '${nodeId.value}' (task ${taskId.value}) failed: ${cause.message}"

  /** The graph went quiescent while a join still waited for arrivals that nothing can produce. */
  final case class UnsatisfiedJoin(joinId: JoinId, missing: List[String]) extends GraphError:
    override val message: String =
      s"Join '${joinId.value}' can never release; still waiting for ${missing.mkString(", ")}"

  /**
   * The run was cancelled by interrupting its thread. Every task was interrupted and joined. In a
   * durable run, tasks that finished first keep their results as pending writes, and the
   * checkpoint stays `Running`, so `recover` continues it without re-running them. An in-memory
   * `CompiledGraph.run` keeps nothing from the cancelled superstep: its state is the state before
   * that superstep. The interrupt flag is set when this is returned.
   */
  final case class Cancelled(threadId: Option[String], lastCheckpoint: Option[String]) extends GraphError:
    override val message: String =
      s"Run${threadId.fold("")(t => s" on thread '$t'")} was cancelled${lastCheckpoint.fold("")(c => s"; recover from $c")}"

  /** The run reached its superstep limit with work still scheduled. */
  final case class SuperstepLimitExceeded(limit: Int) extends GraphError:
    override val message: String = s"Graph exceeded its limit of $limit supersteps"

  /** An execution was passed to a graph other than the one that created or restored it. */
  final case class ForeignExecution(graphId: String) extends GraphError:
    override val message: String = s"Execution does not belong to graph '$graphId'"

  /** A snapshot does not match this compiled graph; every problem is listed. */
  final case class RestoreRejected(graphId: String, problems: List[String]) extends GraphError:
    override val message: String = s"Snapshot cannot be restored into graph '$graphId': ${problems.mkString("; ")}"

  /** A checkpoint was written in a format this build does not read. */
  final case class UnsupportedCheckpointFormat(found: Int, supported: Int) extends GraphError:
    override val message: String = s"Checkpoint format $found is not supported; this build reads up to $supported"

  /** A new checkpoint's parent is not the thread's latest: another writer advanced the thread. */
  final case class CheckpointConflict(threadId: String, expected: Option[String], actual: Option[String])
      extends GraphError:
    override val message: String =
      s"Thread '$threadId' is at checkpoint ${actual.getOrElse("<none>")}, not ${expected.getOrElse("<none>")}"

  /** A commit is malformed, such as a pending write naming a checkpoint other than the latest. */
  final case class InvalidCommit(threadId: String, reason: String) extends GraphError:
    override val message: String = s"Invalid commit to thread '$threadId': $reason"

  /** Events before `earliestSeq` were compacted away and cannot be replayed. */
  final case class ReplayUnavailable(threadId: String, earliestSeq: Long) extends GraphError:
    override val message: String = s"Thread '$threadId' can replay events from sequence $earliestSeq only"

  /** `start` was called on a thread whose latest checkpoint is mid-execution; use `recover`. */
  final case class IncompleteRun(threadId: String, checkpointId: String) extends GraphError:
    override val message: String =
      s"Thread '$threadId' has an incomplete execution at checkpoint $checkpointId; recover it before starting a new run"

  /** `recover` was called on a thread with no incomplete execution. */
  final case class NothingToRecover(threadId: String) extends GraphError:
    override val message: String = s"Thread '$threadId' has no incomplete execution to recover"

  /**
   * The checkpointer refused or failed a commit; the run stops at its last durable checkpoint.
   * `runError` is the run's own failure when the commit failed while recording it.
   */
  final case class CheckpointWriteFailed(threadId: String, cause: LLMError, runError: Option[LLMError] = None)
      extends GraphError:
    override val message: String =
      s"Checkpoint write for thread '$threadId' failed: ${cause.message}" +
        runError.fold("")(e => s" (while recording the run's failure: ${e.message})")

  /** A resume was refused: no answers, an interrupt that is not parked, or an answer that does not decode. */
  final case class InvalidResume(graphId: String, problems: List[String]) extends GraphError:
    override val message: String = s"Cannot resume graph '$graphId': ${problems.mkString("; ")}"

  /** `start` or `recover` was called on a suspended thread; answer its interrupts with `resume`. */
  final case class PendingInterrupts(threadId: String, interrupts: List[String]) extends GraphError:
    override val message: String =
      s"Thread '$threadId' is suspended on ${interrupts.mkString(", ")}; resume it with answers"

  /**
   * The run's tenant is not the thread's: a thread belongs to the tenant recorded on its latest
   * checkpoint. `None` and `Some` differ. Nothing was changed.
   */
  final case class TenantMismatch(threadId: String, expected: Option[String], actual: Option[String])
      extends GraphError:
    override val message: String =
      s"Thread '$threadId' belongs to tenant ${expected.fold("<none>")(t => s"'$t'")}, " +
        s"not ${actual.fold("<none>")(t => s"'$t'")}"

  /** `resume` was called on a thread that is not suspended. */
  final case class NotSuspended(threadId: String) extends GraphError:
    override val message: String = s"Thread '$threadId' has no suspended run to resume"

  /**
   * Another run holds the thread: it is still executing in this runtime, or it claimed the thread
   * between this call reading it and claiming it. Nothing was accepted or discarded - retry once
   * that run has suspended or completed.
   */
  final case class ThreadBusy(threadId: String, latestCheckpoint: Option[String]) extends GraphError:
    override val message: String =
      s"Thread '$threadId' is held by another run (now at ${latestCheckpoint.getOrElse("<none>")}); retry"
