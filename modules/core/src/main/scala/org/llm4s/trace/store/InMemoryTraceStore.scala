package org.llm4s.trace.store

import cats.Id
import org.llm4s.trace.model.{ Span, Trace }
import org.llm4s.types.TraceId

/**
 * Thread-safe, in-process `TraceStore[Id]` backed by immutable maps.
 *
 *  All operations are synchronous and never fail (`F = cats.Id`).
 *  Intended for testing and single-process observability. All state is lost
 *  when the JVM exits. Use `InMemoryTraceStore()` to construct.
 */
class InMemoryTraceStore extends TraceStore[Id] {

  private var traces: Map[TraceId, Trace]     = Map.empty
  private var spans: Map[TraceId, List[Span]] = Map.empty

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

  /**
   * Returns a page of traces matching the query, ordered by start time ascending.
   *
   * == Cursor semantics ==
   * The `nextCursor` in the returned `TracePage` is the `traceId` string of the
   * last trace on the current page.  Pass it back as `query.cursor` to fetch the
   * next page.  An unrecognised cursor (e.g. from a deleted trace or a stale
   * reference) is treated as absent — the query starts from the beginning of the
   * result set rather than returning an error.
   *
   * == Filter combination ==
   * All non-empty filter fields in `TraceQuery` are combined with AND semantics.
   */
  override def queryTraces(query: TraceQuery): TracePage = synchronized {
    val filtered = traces.values.toList
      .filter(t => query.startTimeFrom.forall(from => !t.startTime.isBefore(from)))
      .filter(t => query.startTimeTo.forall(to => !t.startTime.isAfter(to)))
      .filter(t => query.status.forall(_ == t.status))
      .filter(t => query.metadata.forall { case (k, v) => t.metadata.get(k).contains(v) })

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

  /**
   * Removes the trace and all associated spans from the store.
   *
   * Both the trace record and every span with the same `traceId` are deleted in
   * a single synchronised operation.  Returns `false` when no trace with the
   * given `traceId` exists; span cleanup is still attempted in that case.
   *
   * @return `true` when the trace existed and was removed; `false` otherwise
   */
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
