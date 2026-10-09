package org.llm4s.agent.graph

import org.llm4s.error.LLMError
import upickle.default.ReadWriter

import java.time.Instant

/**
 * What happened in a run. Durable: persisted in the thread's event log and replayable.
 *
 * The three events that begin a run record the run's `tenantId` and `principal` (as supplied in its
 * [[RunConfig]]); the log reads events written before they existed as having neither.
 */
enum RunEvent derives ReadWriter:
  case RunStarted(tenantId: Option[String], principal: Option[String])
  case RunRecovered(fromCheckpoint: String, tenantId: Option[String], principal: Option[String])
  case TaskCompleted
  case TaskFailed(message: String)

  /** The task parked a continuation; its pending write holds the question. */
  case TaskSuspended(interruptId: String)
  case CheckpointCommitted(superstep: Int)
  case RunCompleted

  /** The run paused; these interrupts await answers. */
  case RunSuspended(interrupts: Vector[String])

  /** A run began by answering these interrupts. */
  case RunResumed(answered: Vector[String], tenantId: Option[String], principal: Option[String])
  case RunFailed(message: String)

  /** The run was cancelled by interrupting its thread; its checkpoint stays `Running` for `recover`. */
  case RunCancelled

  /** The run's deadline expired, stopping it as a cancel does; its checkpoint stays `Running` for `recover`. */
  case RunTimedOut

  /**
   * [[GraphRuntime.updateState]] committed a new checkpoint over `fromCheckpoint`, with a state update
   * applied as `asNode` (the event's `nodeId` too) when one was named.
   */
  case StateUpdated(
    fromCheckpoint: String,
    asNode: Option[String],
    tenantId: Option[String],
    principal: Option[String]
  )

  /**
   * [[GraphRuntime.fork]] created this thread from checkpoint `fromCheckpoint` of thread `fromThread`;
   * the first event of the new thread's log.
   */
  case ThreadForked(
    fromThread: String,
    fromCheckpoint: String,
    tenantId: Option[String],
    principal: Option[String]
  )

  /** A node's own event, from [[RunContext.emit]]; `name` and `version` identify its payload. */
  case Custom(name: String, version: Int, payload: ujson.Value)

/** A durable event before its commit assigns it a sequence number. */
final case class EventDraft(
  runId: String,
  checkpointId: Option[String],
  taskId: Option[String],
  nodeId: Option[String],
  timestamp: Instant,
  event: RunEvent
)

/**
 * A committed durable event. `seq` is per thread, allocated in the commit that stored the event,
 * contiguous and never reused; an event is delivered to subscribers only after that commit.
 */
final case class EventRecord(
  threadId: String,
  seq: Long,
  runId: String,
  checkpointId: Option[String],
  taskId: Option[String],
  nodeId: Option[String],
  timestamp: Instant,
  event: RunEvent
)

object EventRecord:
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
          upgraded.objOpt.foreach(o => o.get("event").foreach(e => o("event") = upgradedEvent(e)))
          upickle.default.read[EventRecord](upgraded)(using derived)
      )

  def committed(threadId: String, seq: Long, draft: EventDraft): EventRecord =
    EventRecord(
      threadId,
      seq,
      draft.runId,
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
 */
enum StreamEvent:
  /** A committed durable event, in ascending `seq` order. */
  case Durable(record: EventRecord)

  /**
   * Live-only progress from [[RunContext.progress]]: never persisted, never replayed, no `seq`.
   * `name` and `version` identify the payload, as for [[RunEvent.Custom]].
   */
  case Live(
    threadId: String,
    runId: String,
    taskId: String,
    nodeId: String,
    name: String,
    version: Int,
    payload: ujson.Value
  )

  /** `dropped` live events were discarded at this point because the subscriber's queue was full. */
  case LiveGap(dropped: Int)

  /**
   * The subscription has ended; always the last event. `lastSeq` is the last durable `seq` the
   * listener returned from: resubscribe with `afterSeq = lastSeq` to continue without a gap.
   */
  case Disconnected(lastSeq: Long, reason: DisconnectReason)

/** Why a subscription ended with [[StreamEvent.Disconnected]]. */
enum DisconnectReason:
  /** A durable event did not fit in the subscriber's queue: the listener fell too far behind. */
  case Lagging

  /** The listener threw `cause`; the event it threw on is not counted as delivered. */
  case ListenerFailed(cause: Throwable)

  /** Reading the thread's event log failed while replaying. */
  case ReplayFailed(error: LLMError)

/**
 * A registration with [[GraphRuntime.subscribe]] or [[RunHandle.subscribe]]. It is scoped to a
 * thread, not a run: it keeps delivering the events of every later run on the same thread, and its
 * dispatcher - a virtual thread, parked while there is nothing to deliver - lives until [[cancel]]
 * is called or the subscription is disconnected. Cancel every subscription you no longer need.
 *
 * Live delivery covers only commits made through the [[GraphRuntime]] it was made on. Commits by
 * another runtime or process sharing the same [[Checkpointer]] are not pushed to it; they become
 * visible by subscribing again, which replays the log. Store-level change notification arrives with
 * Stage 2's durable checkpointer backends.
 */
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
