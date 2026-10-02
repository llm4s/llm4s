package org.llm4s.agent.graph

import upickle.default.ReadWriter

import java.time.Instant

/** What happened in a run. Durable: persisted in the thread's event log and replayable. */
enum RunEvent derives ReadWriter:
  case RunStarted
  case RunRecovered(fromCheckpoint: String)
  case TaskCompleted
  case TaskFailed(message: String)
  case CheckpointCommitted(superstep: Int)
  case RunCompleted
  case RunFailed(message: String)

  /** A node's own event, from [[NodeContext.emit]]; `name` and `version` identify its payload. */
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
  given ReadWriter[EventRecord]     = upickle.default.macroRW

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

/** What a subscriber receives. */
enum StreamEvent:
  /** A committed durable event, in ascending `seq` order. */
  case Durable(record: EventRecord)

  /** Live-only progress from [[NodeContext.progress]]: never persisted, never replayed, no `seq`. */
  case Live(threadId: String, runId: String, taskId: String, nodeId: String, payload: ujson.Value)

/** A registration with [[GraphRuntime.subscribe]]. */
trait Subscription:
  def cancel(): Unit
