package org.llm4s.samples.basic

import cats.Id
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.trace.store.{ InMemoryTraceStore, TraceQuery }
import org.llm4s.trace.{ ConsoleTracing, TraceCollectorTracing, TraceEvent, TracingComposer }
import org.llm4s.types.Result

/**
 * Sample recording trace events in memory with `TraceCollectorTracing` and reading them back.
 *
 * Unlike `LangfuseTracing`, which sends events to a Langfuse server, the collector keeps them as spans in a
 * `TraceStore` you can query in the same process, so it needs no server, network or key.
 *
 * The events are written by hand: nothing here calls a provider or reads configuration.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.basic.TraceCollectorExample"
 * }}}
 */
object TraceCollectorExample {

  private val Model = "fake-model"

  private def run(store: InMemoryTraceStore): Result[Unit] =
    for {
      collector <- TraceCollectorTracing[Id](store)

      // 1. Record a tool call, some events and an error
      _ <- collector.traceEvent(TraceEvent.AgentInitialized("What is 2 + 2?", Vector("calculator")))
      _ <- collector.traceToolCall("calculator", """{"expression":"2 + 2"}""", "4")
      _ <- collector.traceEvent(TraceEvent.CompletionReceived("completion-1", Model, 0, "2 + 2 is 4."))
      _ <- collector.traceEvent(TraceEvent.TokenUsageRecorded(TokenUsage(12, 7, 19), Model, "completion"))
      _ <- collector.traceError(new IllegalStateException("model call timed out"), "second question")

      // 2. Read the spans back
      _ = println("=== Spans in the store ===")
      _ = store.getSpans(collector.traceId).foreach { span =>
        println(s"${span.name} | kind=${span.kind} | status=${span.status}")
      }

      // 3. Query the store. The collector saves its trace as Running when it is created, and leaves it that way.
      page = store.queryTraces(TraceQuery.withLimit(10))
      _    = println(s"\n=== Query: ${page.size} trace(s), more pages: ${page.hasNext} ===")
      _ = page.traces.foreach { trace =>
        println(s"${trace.traceId.value} | status=${trace.status} | spans=${store.getSpans(trace.traceId).size}")
      }

      // 4. Send the same events to the console and the store. The events are fake, so the console may show their content.
      _      = println("\n=== Console and store together ===")
      before = store.getSpans(collector.traceId).size
      both   = TracingComposer.combine(collector, new ConsoleTracing(captureContent = true))
      _ <- both.traceEvent(TraceEvent.AgentInitialized("What is 3 + 3?", Vector("calculator")))
      _ <- both.traceToolCall("calculator", """{"expression":"3 + 3"}""", "6")
      // The composer turns traceToolCall into a TraceEvent, so this span is named after the event, not tool:calculator.
      _ = println(
        s"\nNew spans in the store: ${store.getSpans(collector.traceId).drop(before).map(_.name).mkString(", ")}"
      )
    } yield ()

  def main(args: Array[String]): Unit =
    run(InMemoryTraceStore()) match {
      case Right(_) =>
      case Left(error) =>
        System.err.println(s"Trace collector example failed: ${error.message}")
        sys.exit(1)
    }
}
