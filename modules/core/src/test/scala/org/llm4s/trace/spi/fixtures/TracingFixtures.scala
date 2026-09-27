package org.llm4s.trace.spi.fixtures

import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.trace.{ NoOpTracing, Tracing, TracingMode }
import org.llm4s.types.Result

/** The tracing a fixture backend builds, recording which backend built it. */
final class FixtureTracing(val builtBy: String, val settings: TracingSettings) extends NoOpTracing

/**
 * A tracing backend that exists only to be discovered.
 *
 * It stands in for a backend outside `llm4s-core` - a Datadog or Honeycomb
 * integration, say: nothing in core mentions it, its mode is a
 * `TracingMode.Named`, and the only thing that makes it reachable is a
 * `META-INF/services` entry on the class loader under test.
 */
final class FixtureTracingBackend extends TracingBackend:
  val mode: TracingMode = TracingMode.Named("fixture")

  def create(settings: TracingSettings): Result[Tracing] =
    Right(new FixtureTracing("FixtureTracingBackend", settings))

/** A second backend for the same mode, to show which registration wins. */
final class OtherFixtureTracingBackend extends TracingBackend:
  val mode: TracingMode = TracingMode.Named("fixture")

  def create(settings: TracingSettings): Result[Tracing] =
    Right(new FixtureTracing("OtherFixtureTracingBackend", settings))

/** A backend whose own code fails when asked to build its tracer. */
final class ThrowingTracingBackend extends TracingBackend:
  val mode: TracingMode = TracingMode.Named("throwing")

  def create(settings: TracingSettings): Result[Tracing] =
    throw new IllegalStateException("this backend is broken")

/**
 * A backend that fails the way a stale jar does: `AbstractMethodError` is a
 * `LinkageError`, which `scala.util.Try` does not catch.
 */
final class LinkageErrorTracingBackend extends TracingBackend:
  def mode: TracingMode =
    throw new AbstractMethodError("org.llm4s.trace.spi.TracingBackend.mode()")

  def create(settings: TracingSettings): Result[Tracing] =
    Right(new NoOpTracing())
