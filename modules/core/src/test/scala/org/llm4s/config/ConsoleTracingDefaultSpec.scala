package org.llm4s.config

import org.llm4s.llmconnect.model.{ AssistantMessage, UsageSummary, UserMessage }
import org.llm4s.trace.{ ConsoleTracing, TraceEvent, Tracing, TracingMode }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.io.ByteArrayOutputStream
import scala.concurrent.duration._

/**
 * The default tracer prints no prompt or completion content (#2311): with no configuration of
 * the application's own, `Llm4sConfig.tracing()` selects console tracing from core's
 * `reference.conf`, and the events of an agent run with a known query, answer and tool call
 * reach stdout as metadata only. With `llm4s.tracing.console.captureContent = true` they print
 * in full.
 *
 * Core cannot run an agent (`llm4s-agent` depends on core), so the run is the events an agent
 * emits: `AgentInitialized`, the model's `CompletionReceived` and usage, the tool's
 * `ToolExecuted`, the `agent.*` custom events that carry its messages, an `ErrorOccurred` and
 * `AgentRunEnded`.
 */
class ConsoleTracingDefaultSpec extends AnyFlatSpec with Matchers {

  private val query      = "My card is 4111-1111-1111-1111, what is my balance?"
  private val answer     = "Your balance on card 4111-1111-1111-1111 is 42 EUR"
  private val toolInput  = """{"account":"DE89-3704-0044-0532-0130-00"}"""
  private val toolOutput = "balance for DE89-3704-0044-0532-0130-00: 42 EUR"
  private val failure    = "lookup failed for jane.doe@example.com"
  private val secrets    = Seq("4111-1111-1111-1111", "DE89-3704-0044-0532-0130-00", "jane.doe@example.com")

  private def run(tracing: Tracing): String = {
    val out = new ByteArrayOutputStream()
    Console.withOut(out) {
      tracing.traceEvent(TraceEvent.AgentInitialized(query, Vector("balance")))
      tracing.traceEvent(TraceEvent.CustomEvent("agent.turn_started", ujson.Obj("runId" -> "r", "input" -> query)))
      tracing.traceEvent(TraceEvent.CompletionReceived("c-1", "test-model", 1, answer))
      tracing.traceTokenUsage(org.llm4s.llmconnect.model.TokenUsage(20, 10, 30), "test-model", "agent_completion")
      tracing.traceEvent(TraceEvent.ToolExecuted("balance", toolInput, toolOutput, 12.millis, true))
      tracing.traceToolCall("balance", toolInput, toolOutput)
      tracing.traceError(new IllegalStateException(failure), s"while answering: $query")
      tracing.traceEvent(
        TraceEvent.AgentRunEnded(
          "thread-1",
          "run-1",
          "assistant",
          "completed",
          Seq(UserMessage(query), AssistantMessage(answer)),
          UsageSummary()
        )
      )
    }
    out.toString.replaceAll("\u001b\\[[0-9;]*m", "")
  }

  "Tracing built from the default configuration" should "be console tracing that prints no content" in {
    val settings = Llm4sConfig.tracing().fold(e => fail(e.message), identity)
    settings.mode shouldBe TracingMode.Console

    val tracing = Tracing.fromSettings(settings).fold(e => fail(e.message), identity)
    tracing shouldBe a[ConsoleTracing]

    val output = run(tracing)
    output should include("AGENT INITIALIZED")
    output should include("Tools: balance")
    output should include("Model: test-model")
    output should include("Total Tokens: 30")
    output should include("Tool: balance")
    output should include("Duration: 12ms")
    output should include("Type: IllegalStateException")
    output should include("Agent: assistant  Status: completed")
    secrets.foreach(secret => (output should not).include(secret))
    (output should not).include("42 EUR")
  }

  it should "print queries, answers, tool input/output and errors with captureContent = true" in {
    val source   = ConfigSource.string("llm4s.tracing.console.captureContent = true").withFallback(ConfigSource.default)
    val settings = TracingConfigLoader.load(source).fold(e => fail(e.message), identity)
    val output   = run(Tracing.fromSettings(settings).fold(e => fail(e.message), identity))

    output should include(s"Query: $query")
    output should include(s"Content: $answer")
    output should include(s"Input: $toolInput")
    output should include(s"Output: $toolOutput")
    output should include(s"Message: $failure")
    output should include("\"input\":")
  }

  it should "reject a captureContent that is not a boolean" in {
    val source   = ConfigSource.string("llm4s.tracing.console.captureContent = sometimes")
    val settings = TracingConfigLoader.load(source).fold(e => fail(e.message), identity)

    Tracing.fromSettings(settings).left.map(_.message).left.toOption.get should include("captureContent")
    // Tracing.create never fails: it falls back to NoOp rather than printing content.
    (Tracing.create(settings) should not).be(a[ConsoleTracing])
  }
}
