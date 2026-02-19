package org.llm4s.trace.store

import org.llm4s.trace.model.{ Span, Trace }
import org.llm4s.types.TraceId

/**
 * Thread-safe, in-process `TraceStore` backed by immutable maps.
 *
 *  Intended for testing and single-process observability. All state is lost
 *  when the JVM exits. Use `InMemoryTraceStore()` to construct.
 */
class InMemoryTraceStore extends TraceStore {

  @volatile private var traces: Map[TraceId, Trace]     = Map.empty
  @volatile private var spans: Map[TraceId, List[Span]] = Map.empty

  override def saveTrace(trace: Trace): Unit = synchronized {
    traces = traces + (trace.traceId -> trace)
  }

  override def getTrace(traceId: TraceId): Option[Trace] = synchronized {
    traces.get(traceId)
  }

  override def saveSpan(span: Span): Unit = synchronized {
    val existing = spans.getOrElse(span.traceId, List.empty)
    spans = spans + (span.traceId -> (existing :+ span))
  }

  override def getSpans(traceId: TraceId): List[Span] = synchronized {
    spans.getOrElse(traceId, List.empty)
  }

  override def queryTraces(query: TraceQuery): TracePage = synchronized {
    var filtered = traces.values.toList

    query.startTimeFrom.foreach(from => filtered = filtered.filter(t => !t.startTime.isBefore(from)))
    query.startTimeTo.foreach(to => filtered = filtered.filter(t => !t.startTime.isAfter(to)))
    query.status.foreach(s => filtered = filtered.filter(_.status == s))
    if (query.metadata.nonEmpty) {
      filtered = filtered.filter(trace => query.metadata.forall { case (k, v) => trace.metadata.get(k).contains(v) })
    }

    val sorted = filtered.sortBy(_.startTime.toEpochMilli)

    query.cursor match {
      case Some(cursor) =>
        val cursorIndex = sorted.indexWhere(_.traceId.value == cursor)
        if (cursorIndex >= 0) {
          val startIndex = cursorIndex + 1
          val page       = sorted.slice(startIndex, startIndex + query.limit)
          val nextCursor = if (startIndex + query.limit < sorted.length) {
            Some(sorted(startIndex + query.limit - 1).traceId.value)
          } else None
          TracePage(page, nextCursor)
        } else {
          TracePage(sorted.take(query.limit), None)
        }
      case None =>
        val page = sorted.take(query.limit)
        val nextCursor = if (query.limit < sorted.length) {
          Some(sorted(query.limit - 1).traceId.value)
        } else None
        TracePage(page, nextCursor)
    }
  }

  override def searchByMetadata(key: String, value: String): List[TraceId] = synchronized {
    traces.values
      .filter(_.metadata.get(key).contains(value))
      .map(_.traceId)
      .toList
  }

  override def deleteTrace(traceId: TraceId): Boolean = synchronized {
    if (traces.contains(traceId)) {
      traces = traces - traceId
      spans = spans - traceId
      true
    } else false
  }

  override def clear(): Unit = synchronized {
    traces = Map.empty
    spans = Map.empty
  }
}

object InMemoryTraceStore {
  def apply(): InMemoryTraceStore = new InMemoryTraceStore()
}
