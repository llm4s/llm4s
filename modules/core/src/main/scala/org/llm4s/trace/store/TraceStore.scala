package org.llm4s.trace.store

import org.llm4s.trace.model.{ Span, Trace }
import org.llm4s.types.TraceId

/**
 * Persistent store for traces and their spans.
 *
 *  Implementations must be thread-safe. `InMemoryTraceStore` is the reference
 *  implementation; swap in any backend (Postgres, Redis, …) without changing
 *  tracing code.
 */
trait TraceStore {

  /** Persist or overwrite a trace record. */
  def saveTrace(trace: Trace): Unit

  /** Look up a trace by its ID, or `None` if not found. */
  def getTrace(traceId: TraceId): Option[Trace]

  /** Append a span to the trace it belongs to. */
  def saveSpan(span: Span): Unit

  /** Return all spans recorded under the given trace, in insertion order. */
  def getSpans(traceId: TraceId): List[Span]

  /**
   * Return a page of traces matching `query`, sorted by start time ascending.
   *
   *  @param query filters, cursor and page size
   *  @return a page with an optional cursor for the next page
   */
  def queryTraces(query: TraceQuery): TracePage

  /** Return all trace IDs whose metadata contains the given key/value pair. */
  def searchByMetadata(key: String, value: String): List[TraceId]

  /**
   * Remove the trace and all its spans atomically.
   *
   *  @return `true` if the trace existed and was removed, `false` if not found
   */
  def deleteTrace(traceId: TraceId): Boolean

  /** Remove all traces and spans. */
  def clear(): Unit
}
