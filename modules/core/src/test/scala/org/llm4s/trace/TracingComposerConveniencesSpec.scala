package org.llm4s.trace

import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, TokenUsage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import org.llm4s.types.Result

import java.io.ByteArrayOutputStream
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * `TracingComposer.filter` and `transform` apply to everything a tracer is asked to trace, not only to what reaches it
 * through `traceEvent` (#2799): `traceError`, `traceCompletion` and `traceTokenUsage` used to be forwarded straight to
 * the wrapped tracer, past the predicate and the function.
 */
class TracingComposerConveniencesSpec extends AnyFlatSpec with Matchers {

  private val completion =
    Completion("c-1", Instant.EPOCH, "the answer", "test-model", AssistantMessage("the answer"), usage = None)
  private val usage = TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30)

  "Tracing.traceError" should "record an LLMError as it is: its message and type, with no exception (#2800)" in {
    val seen = scala.collection.mutable.Buffer.empty[TraceEvent]
    val tracing = new Tracing {
      def traceEvent(event: TraceEvent): org.llm4s.types.Result[Unit] = { seen += event; Right(()) }
    }
    val error = org.llm4s.error.ProcessingError("store", "store down")
    tracing.traceError(error, "commit") shouldBe Right(())
    tracing.traceError(new IllegalStateException("boom"), "a step") shouldBe Right(())

    val errors = seen.collect { case e: TraceEvent.ErrorOccurred => e }.toSeq
    errors.map(e => (e.message, e.errorType, e.context, e.cause.isDefined)) shouldBe Seq(
      (error.message, "ProcessingError", "commit", false),
      ("boom", "IllegalStateException", "a step", true)
    )
  }

  private def traceAll(tracing: Tracing): Unit = {
    tracing.traceError(new IllegalStateException("boom"), "a step") shouldBe Right(())
    tracing.traceCompletion(completion, "test-model") shouldBe Right(())
    tracing.traceTokenUsage(usage, "test-model", "completion") shouldBe Right(())
  }

  "TracingComposer.transform" should "be given the events of traceError, traceCompletion and traceTokenUsage" in {
    val seen = mutable.Buffer.empty[TraceEvent]
    val transformed = TracingComposer.transform(new NoOpTracing) { event =>
      seen += event
      event
    }

    traceAll(transformed)

    seen.map(_.eventType) shouldBe Seq("error_occurred", "completion_received", "token_usage_recorded")
  }

  "TracingComposer.filter" should "drop the events of traceError, traceCompletion and traceTokenUsage its predicate refuses" in {
    val out      = new ByteArrayOutputStream()
    val filtered = TracingComposer.filter(new ConsoleTracing())(_ => false)

    Console.withOut(out)(traceAll(filtered))

    out.toString shouldBe empty
  }

  it should "pass on the ones its predicate accepts" in {
    val out      = new ByteArrayOutputStream()
    val filtered = TracingComposer.filter(new ConsoleTracing())(_.eventType == "token_usage_recorded")

    Console.withOut(out)(traceAll(filtered))

    val printed = out.toString
    printed should include("Operation: completion")
    (printed should not).include("ERROR OCCURRED")
    (printed should not).include("COMPLETION RECEIVED")
  }

  /** All a tracer has to implement. */
  final private class Recording extends Tracing {
    val events: mutable.Buffer[TraceEvent]          = mutable.Buffer.empty
    var closed                                      = 0
    def traceEvent(event: TraceEvent): Result[Unit] = { events += event; Right(()) }
    override def close(): Unit                      = closed += 1
  }

  "A Tracing that implements only traceEvent" should "receive every convenience as its event" in {
    val recording = new Recording

    traceAll(recording)
    recording.traceToolCall("search", "{}", "[]", 120.millis, success = false) shouldBe Right(())

    recording.events.map(_.eventType) shouldBe Seq(
      "error_occurred",
      "completion_received",
      "token_usage_recorded",
      "tool_executed"
    )
  }

  "traceToolCall" should "record the duration and outcome it is given, not zero and success" in {
    val recording = new Recording

    recording.traceToolCall("search", "{}", "timed out", 120.millis, success = false) shouldBe Right(())

    val tool = recording.events.collect { case e: TraceEvent.ToolExecuted => e }.head
    tool.duration shouldBe 120.millis
    tool.success shouldBe false
  }

  "Tracing" should "be an AutoCloseable whose close does nothing by default" in {
    val closeable: AutoCloseable = new NoOpTracing
    noException should be thrownBy closeable.close()

    val recording = new Recording
    scala.util.Using.resource(recording)(_.traceEvent("started")) shouldBe Right(())
    recording.closed shouldBe 1
  }

  "A combined tracer" should "close every tracer it wraps, whatever an earlier one's close throws" in {
    val failing = new Tracing {
      def traceEvent(event: TraceEvent): Result[Unit] = Right(())
      override def close(): Unit                      = throw new IllegalStateException("exporter gone")
    }
    val recording = new Recording

    noException should be thrownBy TracingComposer.combine(failing, recording).close()
    recording.closed shouldBe 1
  }

  "A filtered and a transformed tracer" should "close the tracer they wrap" in {
    val recording = new Recording

    TracingComposer.filter(recording)(_ => true).close()
    TracingComposer.transform(recording)(identity).close()

    recording.closed shouldBe 2
  }
}
