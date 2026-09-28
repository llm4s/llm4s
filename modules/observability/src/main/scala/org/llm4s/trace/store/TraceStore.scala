package org.llm4s.trace.store

import org.llm4s.trace.model.{ Span, Trace }
import org.llm4s.types.TraceId

/**
 * Pluggable backend for storing traces and their spans.
 *
 *  The effect type `F[_]` abstracts over the execution model:
 *  - [[InMemoryTraceStore]] uses `cats.Id` — synchronous, never fails.
 *  - Future backends (Postgres, Redis, …) can use `IO`, `Future`, or any
 *    effect that natively carries failure, without changing call sites.
 *
 *  Implementations must be thread-safe.
 *
 *  @tparam F effect in which all operations are expressed; no typeclass bound is imposed —
 *            each implementation declares its own semantics (synchronous, async, etc.)
 */
trait TraceStore[F[_]] {

  /** Persist or overwrite a trace record. */
  def saveTrace(trace: Trace): F[Unit]

  /** Look up a trace by its ID, or `None` if not found. */
  def getTrace(traceId: TraceId): F[Option[Trace]]

  /** Append a span to the trace it belongs to. */
  def saveSpan(span: Span): F[Unit]

  /** Return all spans recorded under the given trace, in insertion order. */
  def getSpans(traceId: TraceId): F[List[Span]]

  /**
   * Return a page of traces matching `query`, sorted by start time ascending.
   *
   *  @param query filters, cursor and page size
   */
  def queryTraces(query: TraceQuery): F[TracePage]

  /** Return all trace IDs whose metadata contains the given key/value pair. */
  def searchByMetadata(key: String, value: String): F[List[TraceId]]

  /**
   * Remove the trace and all its spans atomically.
   *
   *  @return `true` if the trace existed and was removed, `false` if not found
   */
  def deleteTrace(traceId: TraceId): F[Boolean]

  /** Remove all traces and spans. */
  def clear(): F[Unit]
}
