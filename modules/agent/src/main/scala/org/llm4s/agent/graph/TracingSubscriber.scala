package org.llm4s.agent.graph

import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Bridges a thread's durable run events to a [[org.llm4s.trace.Tracing]]: each becomes a
 * `TraceEvent.CustomEvent` named `graph.<event>` (`RunStarted` is `graph.run_started`), stamped with
 * the record's timestamp. Its data holds `threadId`, `runId`, `seq`, `checkpointId`, `taskId` and
 * `nodeId` (`null` when absent) and the event's own fields. Live progress is not traced.
 */
object TracingSubscriber:

  private val logger = LoggerFactory.getLogger(getClass)

  /** Subscribes `tracing` to `threadId`'s events with `seq > afterSeq`; cancel the result to detach. */
  def attach(
    runtime: GraphRuntime,
    threadId: ThreadId,
    tracing: Tracing,
    afterSeq: Long = 0L
  ): Result[Subscription] =
    runtime.subscribe(threadId, afterSeq) {
      case StreamEvent.Durable(record) =>
        tracing.traceEvent(toTrace(record)).left.foreach { error =>
          logger.warn(
            s"Tracing ${record.event.productPrefix} (seq ${record.seq}) of ${threadId.value} failed: ${error.message}"
          )
        }
      case StreamEvent.Disconnected(lastSeq, reason) =>
        logger.warn(s"Tracing subscription to ${threadId.value} ended after seq $lastSeq: $reason")
      case _ => ()
    }

  private[graph] def snake(name: String): String =
    name.flatMap(c => if c.isUpper then s"_${c.toLower}" else c.toString).stripPrefix("_")

  private def toTrace(r: EventRecord): TraceEvent.CustomEvent =
    def opt(value: Option[String]): ujson.Value = value.fold[ujson.Value](ujson.Null)(ujson.Str(_))
    val data = ujson.Obj(
      "threadId"     -> r.threadId,
      "runId"        -> r.runId,
      "seq"          -> r.seq.toDouble,
      "checkpointId" -> opt(r.checkpointId),
      "taskId"       -> opt(r.taskId),
      "nodeId"       -> opt(r.nodeId)
    )
    upickle.default.writeJs(r.event) match
      case own: ujson.Obj => own.obj.filterNot((key, _) => key == "$type").foreach((key, value) => data(key) = value)
      case _              => ()
    TraceEvent.CustomEvent(s"graph.${snake(r.event.productPrefix)}", data, r.timestamp)
