package org.llm4s.agent.graph

import org.llm4s.annotation.Experimental
import org.llm4s.agent.delegation.ChildRef
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.UsageSummary
import upickle.default.ReadWriter

import java.time.Instant

/**
 * What happened in a run. Durable: persisted in the thread's event log and replayable.
 *
 * The three events that begin a run record the run's `tenantId` and `principal` (as supplied in its
 * [[RunConfig]]); the log reads events written before they existed as having neither.
 *
 * A case that records facts about a run holds a `...Details` class, which can gain a field without breaking a
 * caller or the stored log: the event is stored flat, its details' fields beside its `$type`. `TaskFailed`,
 * `TaskSuspended`, `CheckpointCommitted` and `RunSuspended` are closed, each exactly its one value, and so is
 * `Custom`, whose `payload` is where a node's own event grows.
 */
@Experimental
enum RunEvent:
  case RunStarted(details: RunStartedDetails)

  /** A run continued an incomplete execution; see [[RunRecoveredDetails]]. */
  case RunRecovered(details: RunRecoveredDetails)
  case TaskCompleted
  case TaskFailed(message: String)

  /** The task parked a continuation; its pending write holds the question. */
  case TaskSuspended(interruptId: String)
  case CheckpointCommitted(superstep: Int)
  case RunCompleted

  /** The run paused; these interrupts await answers. */
  case RunSuspended(interrupts: Vector[String])

  /** A run began by answering interrupts; see [[RunResumedDetails]]. */
  case RunResumed(details: RunResumedDetails)

  /** The run failed; see [[RunFailedDetails]]. */
  case RunFailed(details: RunFailedDetails)

  /**
   * The run was cancelled by interrupting its thread; its checkpoint stays `Running` for `recover`. `details` holds
   * the model usage of the attempts the cancel cut short, and their agent model steps.
   */
  case RunCancelled(details: RunStoppedDetails)

  /**
   * The run's deadline expired, stopping it as a cancel does; its checkpoint stays `Running` for `recover`. `details`
   * holds the model usage of the attempts the deadline cut short, and their agent model steps.
   */
  case RunTimedOut(details: RunStoppedDetails)

  /** [[GraphRuntime.updateState]] committed a new checkpoint; see [[StateUpdatedDetails]]. */
  case StateUpdated(details: StateUpdatedDetails)

  /** [[GraphRuntime.fork]] created this thread; the first event of the new thread's log. See [[ThreadForkedDetails]]. */
  case ThreadForked(details: ThreadForkedDetails)

  /** A node's own event, from [[RunContext.emit]]; `name` and `version` identify its payload. */
  case Custom(name: String, version: Int, payload: ujson.Value)

/**
 * What [[RunEvent.RunStarted]] records: the run's `tenantId` and `principal`, as supplied in its [[RunConfig]].
 */
@Experimental
final case class RunStartedDetails private (tenantId: Option[String], principal: Option[String]):
  def withTenantId(value: String): RunStartedDetails          = copy(tenantId = Some(value))
  def withTenantId(value: Option[String]): RunStartedDetails  = copy(tenantId = value)
  def withPrincipal(value: String): RunStartedDetails         = copy(principal = Some(value))
  def withPrincipal(value: Option[String]): RunStartedDetails = copy(principal = value)

@Experimental
object RunStartedDetails:
  def apply(tenantId: Option[String] = None, principal: Option[String] = None): RunStartedDetails =
    new RunStartedDetails(tenantId, principal)

/**
 * What [[RunEvent.RunRecovered]] records: a run continued the incomplete execution at `fromCheckpoint`.
 * `takenOverFrom` is the run id of another run whose claim on the thread had expired, unreleased, and which this
 * run's claim replaced: a takeover, so that run may still have been executing. It can equal this run's own id, when a
 * caller reuses a `RunId` ([[RunConfig.withRunId]]): still a takeover of a separate, earlier run. `None` when the
 * thread was free, and when the replaced claim was another tenant's or recorded no tenant: the log is the owner's, so
 * it never names another tenant's run ([[RunClaim.takenOverFrom]]). A log written before the field existed reads as
 * `None`.
 */
@Experimental
final case class RunRecoveredDetails private (
  fromCheckpoint: String,
  tenantId: Option[String],
  principal: Option[String],
  takenOverFrom: Option[String]
):
  def withFromCheckpoint(value: String): RunRecoveredDetails        = copy(fromCheckpoint = value)
  def withTenantId(value: String): RunRecoveredDetails              = copy(tenantId = Some(value))
  def withTenantId(value: Option[String]): RunRecoveredDetails      = copy(tenantId = value)
  def withPrincipal(value: String): RunRecoveredDetails             = copy(principal = Some(value))
  def withPrincipal(value: Option[String]): RunRecoveredDetails     = copy(principal = value)
  def withTakenOverFrom(value: String): RunRecoveredDetails         = copy(takenOverFrom = Some(value))
  def withTakenOverFrom(value: Option[String]): RunRecoveredDetails = copy(takenOverFrom = value)

@Experimental
object RunRecoveredDetails:
  def apply(
    fromCheckpoint: String,
    tenantId: Option[String] = None,
    principal: Option[String] = None,
    takenOverFrom: Option[String] = None
  ): RunRecoveredDetails =
    new RunRecoveredDetails(fromCheckpoint, tenantId, principal, takenOverFrom)

/** What [[RunEvent.RunResumed]] records: the interrupts the run began by answering, and the run's identity. */
@Experimental
final case class RunResumedDetails private (
  answered: Vector[String],
  tenantId: Option[String],
  principal: Option[String]
):
  def withAnswered(value: Vector[String]): RunResumedDetails  = copy(answered = value)
  def withTenantId(value: String): RunResumedDetails          = copy(tenantId = Some(value))
  def withTenantId(value: Option[String]): RunResumedDetails  = copy(tenantId = value)
  def withPrincipal(value: String): RunResumedDetails         = copy(principal = Some(value))
  def withPrincipal(value: Option[String]): RunResumedDetails = copy(principal = value)

@Experimental
object RunResumedDetails:
  def apply(
    answered: Vector[String],
    tenantId: Option[String] = None,
    principal: Option[String] = None
  ): RunResumedDetails =
    new RunResumedDetails(answered, tenantId, principal)

/**
 * What [[RunEvent.RunFailed]] records: the run failed with `message`. `usage` is the model usage of the attempts of
 * its tasks that committed nothing - a task that failed, or was cut short - which is the only durable record of their
 * calls' cost (design §4.22). `None` when they made none. `steps` is how many of those attempts were an agent's model
 * step - its own model call, not a middleware's - which a recovered child counts against its step grant (design
 * 4.45).
 */
@Experimental
final case class RunFailedDetails private (message: String, usage: Option[UsageSummary], steps: Long):
  def withMessage(value: String): RunFailedDetails             = copy(message = value)
  def withUsage(value: UsageSummary): RunFailedDetails         = copy(usage = Some(value))
  def withUsage(value: Option[UsageSummary]): RunFailedDetails = copy(usage = value)
  def withSteps(value: Long): RunFailedDetails                 = copy(steps = value)

@Experimental
object RunFailedDetails:
  def apply(message: String, usage: Option[UsageSummary] = None, steps: Long = 0L): RunFailedDetails =
    new RunFailedDetails(message, usage, steps)

/**
 * What [[RunEvent.RunCancelled]] and [[RunEvent.RunTimedOut]] record: `usage` is the model usage of the attempts the
 * stop cut short, and `steps` their agent model steps, as on [[RunFailedDetails]].
 */
@Experimental
final case class RunStoppedDetails private (usage: Option[UsageSummary], steps: Long):
  def withUsage(value: UsageSummary): RunStoppedDetails         = copy(usage = Some(value))
  def withUsage(value: Option[UsageSummary]): RunStoppedDetails = copy(usage = value)
  def withSteps(value: Long): RunStoppedDetails                 = copy(steps = value)

@Experimental
object RunStoppedDetails:
  def apply(usage: Option[UsageSummary] = None, steps: Long = 0L): RunStoppedDetails =
    new RunStoppedDetails(usage, steps)

  /** No usage and no steps. */
  val empty: RunStoppedDetails = RunStoppedDetails()

/**
 * What [[RunEvent.StateUpdated]] records: [[GraphRuntime.updateState]] committed a new checkpoint over
 * `fromCheckpoint`, with a state update applied as `asNode` (the event's `nodeId` too) when one was named.
 */
@Experimental
final case class StateUpdatedDetails private (
  fromCheckpoint: String,
  asNode: Option[String],
  tenantId: Option[String],
  principal: Option[String]
):
  def withFromCheckpoint(value: String): StateUpdatedDetails    = copy(fromCheckpoint = value)
  def withAsNode(value: String): StateUpdatedDetails            = copy(asNode = Some(value))
  def withAsNode(value: Option[String]): StateUpdatedDetails    = copy(asNode = value)
  def withTenantId(value: String): StateUpdatedDetails          = copy(tenantId = Some(value))
  def withTenantId(value: Option[String]): StateUpdatedDetails  = copy(tenantId = value)
  def withPrincipal(value: String): StateUpdatedDetails         = copy(principal = Some(value))
  def withPrincipal(value: Option[String]): StateUpdatedDetails = copy(principal = value)

@Experimental
object StateUpdatedDetails:
  def apply(
    fromCheckpoint: String,
    asNode: Option[String] = None,
    tenantId: Option[String] = None,
    principal: Option[String] = None
  ): StateUpdatedDetails =
    new StateUpdatedDetails(fromCheckpoint, asNode, tenantId, principal)

/**
 * What [[RunEvent.ThreadForked]] records: [[GraphRuntime.fork]] created this thread from checkpoint `fromCheckpoint`
 * of thread `fromThread`.
 */
@Experimental
final case class ThreadForkedDetails private (
  fromThread: String,
  fromCheckpoint: String,
  tenantId: Option[String],
  principal: Option[String]
):
  def withFromThread(value: String): ThreadForkedDetails        = copy(fromThread = value)
  def withFromCheckpoint(value: String): ThreadForkedDetails    = copy(fromCheckpoint = value)
  def withTenantId(value: String): ThreadForkedDetails          = copy(tenantId = Some(value))
  def withTenantId(value: Option[String]): ThreadForkedDetails  = copy(tenantId = value)
  def withPrincipal(value: String): ThreadForkedDetails         = copy(principal = Some(value))
  def withPrincipal(value: Option[String]): ThreadForkedDetails = copy(principal = value)

@Experimental
object ThreadForkedDetails:
  def apply(
    fromThread: String,
    fromCheckpoint: String,
    tenantId: Option[String] = None,
    principal: Option[String] = None
  ): ThreadForkedDetails =
    new ThreadForkedDetails(fromThread, fromCheckpoint, tenantId, principal)

/** A durable event before its commit assigns it a sequence number. */
@Experimental
final case class EventDraft private (
  runId: String,
  checkpointId: Option[String],
  taskId: Option[String],
  nodeId: Option[String],
  timestamp: Instant,
  event: RunEvent
):
  def withRunId(value: String): EventDraft                = copy(runId = value)
  def withCheckpointId(value: String): EventDraft         = copy(checkpointId = Some(value))
  def withCheckpointId(value: Option[String]): EventDraft = copy(checkpointId = value)
  def withTaskId(value: String): EventDraft               = copy(taskId = Some(value))
  def withTaskId(value: Option[String]): EventDraft       = copy(taskId = value)
  def withNodeId(value: String): EventDraft               = copy(nodeId = Some(value))
  def withNodeId(value: Option[String]): EventDraft       = copy(nodeId = value)
  def withTimestamp(value: Instant): EventDraft           = copy(timestamp = value)
  def withEvent(value: RunEvent): EventDraft              = copy(event = value)

@Experimental
object EventDraft:
  def apply(
    runId: String,
    checkpointId: Option[String],
    taskId: Option[String],
    nodeId: Option[String],
    timestamp: Instant,
    event: RunEvent
  ): EventDraft =
    new EventDraft(runId, checkpointId, taskId, nodeId, timestamp, event)

/**
 * A committed durable event. `seq` is per thread, allocated in the commit that stored the event,
 * contiguous and never reused; an event is delivered to subscribers only after that commit.
 *
 * `token` is the [[FencingToken]] of the claim the commit was fenced by ([[Commit.token]]): one
 * incarnation of a run. A `RunId` may be reused - a recovery under the same id, or any run given one
 * with [[RunConfig.withRunId]] - so two runs on a thread can share an id, but never a token: the
 * store issues each claim a token above every earlier one. A record written before tokens were
 * recorded reads as `FencingToken(0)`, which no store issues.
 */
@Experimental
final case class EventRecord private (
  threadId: String,
  seq: Long,
  runId: String,
  token: FencingToken,
  checkpointId: Option[String],
  taskId: Option[String],
  nodeId: Option[String],
  timestamp: Instant,
  event: RunEvent
):
  def withThreadId(value: String): EventRecord             = copy(threadId = value)
  def withSeq(value: Long): EventRecord                    = copy(seq = value)
  def withRunId(value: String): EventRecord                = copy(runId = value)
  def withToken(value: FencingToken): EventRecord          = copy(token = value)
  def withCheckpointId(value: String): EventRecord         = copy(checkpointId = Some(value))
  def withCheckpointId(value: Option[String]): EventRecord = copy(checkpointId = value)
  def withTaskId(value: String): EventRecord               = copy(taskId = Some(value))
  def withTaskId(value: Option[String]): EventRecord       = copy(taskId = value)
  def withNodeId(value: String): EventRecord               = copy(nodeId = Some(value))
  def withNodeId(value: Option[String]): EventRecord       = copy(nodeId = value)
  def withTimestamp(value: Instant): EventRecord           = copy(timestamp = value)
  def withEvent(value: RunEvent): EventRecord              = copy(event = value)

@Experimental
object EventRecord:

  def apply(
    threadId: String,
    seq: Long,
    runId: String,
    token: FencingToken,
    checkpointId: Option[String],
    taskId: Option[String],
    nodeId: Option[String],
    timestamp: Instant,
    event: RunEvent
  ): EventRecord =
    new EventRecord(threadId, seq, runId, token, checkpointId, taskId, nodeId, timestamp, event)

  private given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)

  private val identified = Set("RunStarted", "RunRecovered", "RunResumed")

  /**
   * Events written before tenants: `RunStarted` was a bare string, and none of the three events that
   * begin a run had identity fields. A durable log is read through this, so it keeps reading.
   */
  private[graph] def upgradedEvent(json: ujson.Value): ujson.Value =
    val tagged = json match
      case ujson.Str("RunStarted") => ujson.Obj("$type" -> "RunStarted")
      case other                   => other
    tagged match
      case o: ujson.Obj if o.value.get("$type").exists(_.strOpt.exists(identified)) =>
        val copy = ujson.copy(o)
        Seq("tenantId", "principal").foreach(k => if !copy.obj.contains(k) then copy(k) = ujson.Null)
        copy
      case other => other

  given ReadWriter[EventRecord] =
    val derived: ReadWriter[EventRecord] = upickle.default.macroRW
    upickle.default
      .readwriter[ujson.Value]
      .bimap[EventRecord](
        record => upickle.default.writeJs(record)(using derived),
        json =>
          val upgraded = ujson.copy(json)
          upgraded.objOpt.foreach { o =>
            o.get("event").foreach(e => o("event") = upgradedEvent(e))
            // a record written before tokens were recorded (#1918): no claim, older than every one
            if !o.contains("token") then o("token") = ujson.Num(0)
          }
          upickle.default.read[EventRecord](upgraded)(using derived)
      )

  /** The record a store keeps for `draft`, numbered `seq` in a commit fenced by `token`. */
  def committed(threadId: String, seq: Long, token: FencingToken, draft: EventDraft): EventRecord =
    EventRecord(
      threadId,
      seq,
      draft.runId,
      token,
      draft.checkpointId,
      draft.taskId,
      draft.nodeId,
      draft.timestamp,
      draft.event
    )

/**
 * What a subscriber receives. A payload - of a [[StreamEvent.Live]] or a [[RunEvent.Custom]] - is
 * a snapshot taken when the node emitted it, and one value may be shared by every subscriber: a
 * listener must not mutate it, and copies it (`ujson.copy`) to change it.
 *
 * `Live` holds a [[LiveEvent]] and `Durable` an [[EventRecord]], both of which can gain a field. `LiveGap`, `Nested`
 * and `Disconnected` are closed: each is exactly the fields it has.
 */
@Experimental
enum StreamEvent:
  /** A committed durable event, in ascending `seq` order. */
  case Durable(record: EventRecord)

  /**
   * Live-only progress from [[RunContext.progress]]: never persisted, never replayed, no `seq`. See [[LiveEvent]].
   */
  case Live(event: LiveEvent)

  /**
   * `dropped` live events were discarded at this point because the subscriber's queue was full. Inside a
   * [[Nested]], `0` means a delegated child's live events were sent where this subscription could not receive them -
   * by another runtime holding the child, or before a crash, for a child already settled (design 4.47) - so how
   * many is unknown; its durable events are kept, and `Agent.subscribeChild` replays them.
   */
  case LiveGap(dropped: Int)

  /**
   * An event of the delegated child run `child` (design 4.47): live-only, never persisted in the parent's log, and
   * delivered only to a parent subscription made with `nested = true` (`Agent.stream`, `AgentRun.subscribe`,
   * [[GraphRuntime.subscribe]]). It follows the parent's live `SubagentEvents.TaskStarted` for the child, and carries
   * every event of the child's run - durable, live, a `LiveGap` - up to its terminal event; a grandchild's events
   * arrive nested twice. It takes a live slot of the parent's subscription, so an overflow is a `LiveGap` there
   * (rule 12). `Disconnected` is never nested: the child's forwarding ends without one.
   */
  case Nested(child: ChildRef, event: StreamEvent)

  /**
   * The subscription has ended; always the last event. `lastSeq` is the last durable `seq` the
   * listener returned from: resubscribe with `afterSeq = lastSeq` to continue without a gap.
   */
  case Disconnected(lastSeq: Long, reason: DisconnectReason)

/**
 * Live-only progress, the content of a [[StreamEvent.Live]]. `name` and `version` identify the payload, as for
 * [[RunEvent.Custom]]. `token` is the [[FencingToken]] of the claim the sending run held, as on its
 * [[EventRecord]]s: a node of a run that lost its claim and ignores the interrupt may still send progress after a
 * later run of the same `RunId` has claimed the thread, and only the token tells the two apart.
 */
@Experimental
final case class LiveEvent private (
  threadId: String,
  runId: String,
  token: FencingToken,
  taskId: String,
  nodeId: String,
  name: String,
  version: Int,
  payload: ujson.Value
):
  def withThreadId(value: String): LiveEvent     = copy(threadId = value)
  def withRunId(value: String): LiveEvent        = copy(runId = value)
  def withToken(value: FencingToken): LiveEvent  = copy(token = value)
  def withTaskId(value: String): LiveEvent       = copy(taskId = value)
  def withNodeId(value: String): LiveEvent       = copy(nodeId = value)
  def withName(value: String): LiveEvent         = copy(name = value)
  def withVersion(value: Int): LiveEvent         = copy(version = value)
  def withPayload(value: ujson.Value): LiveEvent = copy(payload = value)

@Experimental
object LiveEvent:
  def apply(
    threadId: String,
    runId: String,
    token: FencingToken,
    taskId: String,
    nodeId: String,
    name: String,
    version: Int,
    payload: ujson.Value
  ): LiveEvent =
    new LiveEvent(threadId, runId, token, taskId, nodeId, name, version, payload)

/** Why a subscription ended with [[StreamEvent.Disconnected]]. */
@Experimental
enum DisconnectReason:
  /** A durable event did not fit in the subscriber's queue: the listener fell too far behind. */
  case Lagging

  /** The listener threw `cause`; the event it threw on is not counted as delivered. */
  case ListenerFailed(cause: Throwable)

  /**
   * Reading the thread's event log failed: while replaying, or while reading the commits of another
   * runtime or process sharing the store ([[WatchPolicy]]) - for instance because they were
   * compacted away first ([[GraphError.ReplayUnavailable]]).
   */
  case ReplayFailed(error: LLMError)

  /**
   * The thread was deleted ([[GraphRuntime.deleteThread]], through this runtime or another sharing the
   * store). A subscription follows one thread, not its id: a new thread created under the same id is
   * not delivered to it - subscribe again, from `0`, to follow that one. A subscription in another
   * runtime that had delivered no event yet cannot be told from a new one, so it follows the new
   * thread instead of ending, unless it is another tenant's ([[TenantMismatch]]); see
   * [[GraphRuntime.deleteThread]] for when each subscription learns of the delete.
   */
  case ThreadDeleted

  /**
   * The thread belongs to another tenant than the subscriber's ([[GraphRuntime.subscribe]]'s `config`):
   * it did not exist when the subscription was made, or was deleted and created again through another
   * runtime, and a run of another tenant then made it. Nothing of that thread is delivered: the event
   * that showed it is not, and `lastSeq` is the last event the subscriber was entitled to.
   */
  case TenantMismatch

/**
 * A registration with [[GraphRuntime.subscribe]] or [[RunHandle.subscribe]]. It is scoped to a
 * thread, not a run: it keeps delivering the events of every later run on the same thread, and its
 * dispatcher - a virtual thread, parked while there is nothing to deliver - lives until [[cancel]]
 * is called or the subscription is disconnected. Cancel every subscription you no longer need.
 *
 * It delivers the durable events of every commit on the thread, made through the [[GraphRuntime]]
 * it was made on or through any other runtime or process sharing the same [[Checkpointer]]: the
 * latter it reads from the store as they land ([[WatchPolicy]]). Live progress events reach it only
 * from runs of its own runtime, because they are never stored.
 */
@Experimental
trait Subscription:
  /**
   * Stops the subscription's dispatcher. A listener call already running is interrupted and
   * waited for, so once this returns no call is in progress and none will start - not even
   * [[StreamEvent.Disconnected]]. It therefore blocks for as long as a running listener ignores
   * its interrupt, and two listeners that each cancel the other's subscription can deadlock. Called
   * from the listener itself, it neither interrupts nor waits: it returns at once and the
   * dispatcher stops when the call returns.
   */
  def cancel(): Unit

@Experimental
object RunEvent:

  /**
   * The stored form of a [[RunEvent]]: flat, each case's fields beside its `$type`, as the log has always held them.
   * A field added to a `...Details` class is added here with the same default, so a log written before it still
   * reads; `RunEventWireSpec` pins the JSON.
   */
  private enum Wire derives ReadWriter:
    case RunStarted(tenantId: Option[String], principal: Option[String])
    case RunRecovered(
      fromCheckpoint: String,
      tenantId: Option[String],
      principal: Option[String],
      takenOverFrom: Option[String] = None
    )
    case TaskCompleted
    case TaskFailed(message: String)
    case TaskSuspended(interruptId: String)
    case CheckpointCommitted(superstep: Int)
    case RunCompleted
    case RunSuspended(interrupts: Vector[String])
    case RunResumed(answered: Vector[String], tenantId: Option[String], principal: Option[String])
    case RunFailed(message: String, usage: Option[UsageSummary] = None, steps: Long = 0L)
    case RunCancelled(usage: Option[UsageSummary] = None, steps: Long = 0L)
    case RunTimedOut(usage: Option[UsageSummary] = None, steps: Long = 0L)
    case StateUpdated(
      fromCheckpoint: String,
      asNode: Option[String],
      tenantId: Option[String],
      principal: Option[String]
    )
    case ThreadForked(
      fromThread: String,
      fromCheckpoint: String,
      tenantId: Option[String],
      principal: Option[String]
    )
    case Custom(name: String, version: Int, payload: ujson.Value)

  private def toWire(event: RunEvent): Wire = event match
    case RunStarted(d)            => Wire.RunStarted(d.tenantId, d.principal)
    case RunRecovered(d)          => Wire.RunRecovered(d.fromCheckpoint, d.tenantId, d.principal, d.takenOverFrom)
    case TaskCompleted            => Wire.TaskCompleted
    case TaskFailed(message)      => Wire.TaskFailed(message)
    case TaskSuspended(interrupt) => Wire.TaskSuspended(interrupt)
    case CheckpointCommitted(n)   => Wire.CheckpointCommitted(n)
    case RunCompleted             => Wire.RunCompleted
    case RunSuspended(interrupts) => Wire.RunSuspended(interrupts)
    case RunResumed(d)            => Wire.RunResumed(d.answered, d.tenantId, d.principal)
    case RunFailed(d)             => Wire.RunFailed(d.message, d.usage, d.steps)
    case RunCancelled(d)          => Wire.RunCancelled(d.usage, d.steps)
    case RunTimedOut(d)           => Wire.RunTimedOut(d.usage, d.steps)
    case StateUpdated(d)          => Wire.StateUpdated(d.fromCheckpoint, d.asNode, d.tenantId, d.principal)
    case ThreadForked(d)          => Wire.ThreadForked(d.fromThread, d.fromCheckpoint, d.tenantId, d.principal)
    case Custom(name, version, p) => Wire.Custom(name, version, p)

  private def fromWire(wire: Wire): RunEvent = wire match
    case Wire.RunStarted(tenantId, principal) => RunStarted(RunStartedDetails(tenantId, principal))
    case Wire.RunRecovered(from, tenantId, principal, takenOverFrom) =>
      RunRecovered(RunRecoveredDetails(from, tenantId, principal, takenOverFrom))
    case Wire.TaskCompleted                           => TaskCompleted
    case Wire.TaskFailed(message)                     => TaskFailed(message)
    case Wire.TaskSuspended(interrupt)                => TaskSuspended(interrupt)
    case Wire.CheckpointCommitted(n)                  => CheckpointCommitted(n)
    case Wire.RunCompleted                            => RunCompleted
    case Wire.RunSuspended(interrupts)                => RunSuspended(interrupts)
    case Wire.RunResumed(answered, tenant, principal) => RunResumed(RunResumedDetails(answered, tenant, principal))
    case Wire.RunFailed(message, usage, steps)        => RunFailed(RunFailedDetails(message, usage, steps))
    case Wire.RunCancelled(usage, steps)              => RunCancelled(RunStoppedDetails(usage, steps))
    case Wire.RunTimedOut(usage, steps)               => RunTimedOut(RunStoppedDetails(usage, steps))
    case Wire.StateUpdated(from, asNode, tenantId, principal) =>
      StateUpdated(StateUpdatedDetails(from, asNode, tenantId, principal))
    case Wire.ThreadForked(thread, from, tenantId, principal) =>
      ThreadForked(ThreadForkedDetails(thread, from, tenantId, principal))
    case Wire.Custom(name, version, payload) => Custom(name, version, payload)

  /**
   * Reads and writes the stored form, reading a `RunCancelled` or `RunTimedOut` stored before they carried usage - a
   * bare JSON string - as one with none.
   */
  given ReadWriter[RunEvent] = upickle.default
    .readwriter[ujson.Value]
    .bimap[RunEvent](
      event => upickle.default.writeJs(toWire(event)),
      {
        case ujson.Str("RunCancelled") => RunCancelled(RunStoppedDetails.empty)
        case ujson.Str("RunTimedOut")  => RunTimedOut(RunStoppedDetails.empty)
        case json                      => fromWire(upickle.default.read[Wire](json))
      }
    )
