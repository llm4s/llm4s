package org.llm4s.trace.store

import org.llm4s.trace.model.{ SpanStatus, Trace }

import java.time.Instant

/**
 * Filter and pagination parameters for `TraceStore.queryTraces`.
 *
 *  All filters are optional and combined with AND semantics. Use the companion
 *  object smart constructors for the common single-filter cases.
 *
 *  @param startTimeFrom inclusive lower bound on trace start time
 *  @param startTimeTo   inclusive upper bound on trace start time
 *  @param status        exact status match
 *  @param metadata      key/value pairs that must all be present in the trace metadata
 *  @param cursor        opaque pagination token from the previous `TracePage`
 *  @param limit         maximum number of traces to return (default 50)
 */
final case class TraceQuery(
  startTimeFrom: Option[Instant] = None,
  startTimeTo: Option[Instant] = None,
  status: Option[SpanStatus] = None,
  metadata: Map[String, String] = Map.empty,
  cursor: Option[String] = None,
  limit: Int = 50
)

object TraceQuery {

  /** No filters applied; returns up to 50 traces. */
  def empty: TraceQuery = TraceQuery()

  /** Returns up to `limit` traces with no other filters. */
  def withLimit(limit: Int): TraceQuery = TraceQuery(limit = limit)

  /** Filters to traces whose start time falls within [`from`, `to`] (inclusive). */
  def withTimeRange(from: Instant, to: Instant): TraceQuery =
    TraceQuery(startTimeFrom = Some(from), startTimeTo = Some(to))

  /** Filters to traces with exactly the given status. */
  def withStatus(status: SpanStatus): TraceQuery =
    TraceQuery(status = Some(status))

  /** Filters to traces whose metadata contains the given key/value pair. */
  def withMetadata(key: String, value: String): TraceQuery =
    TraceQuery(metadata = Map(key -> value))
}

/**
 * A single page of traces returned by `TraceStore.queryTraces`.
 *
 *  @param traces     traces on this page, sorted by start time ascending
 *  @param nextCursor opaque token to pass as `TraceQuery.cursor` for the next page;
 *                    `None` if this is the last page
 */
final case class TracePage(
  traces: List[Trace],
  nextCursor: Option[String]
) {

  /** `true` if there are more pages after this one. */
  def hasNext: Boolean = nextCursor.isDefined

  /** `true` if this page contains no traces. */
  def isEmpty: Boolean = traces.isEmpty

  /** Number of traces on this page. */
  def size: Int = traces.length
}
