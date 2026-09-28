package org.llm4s.trace

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.io.ByteArrayOutputStream
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Dedicated coverage for [[NoOpTracing]], the tracer every application gets when tracing is
 * disabled (`TRACING_MODE=none`) and the fallback `Tracing.create` returns when a backend is
 * missing or cannot start (#946).
 *
 * `TracingSpec` already checks that each method returns `Right(())` once, and
 * `TracingEdgeCasesSpec` that `shutdown()` does not throw. This spec covers what those do not:
 * every `TraceEvent` variant, including the `AgentStateUpdated` built by
 * `AgentState#toTraceEvent`; that `NoOpTracing` never reads what it is given; that it writes
 * nothing to stdout, stderr or the log; repeated and concurrent calls; and calls after
 * `shutdown()`.
 *
 * Based on #1102 by @georgxzh, rewritten against the current API: `traceAgentState` was
 * removed in #1233, and agent state is now traced as an ordinary `TraceEvent`.
 */
class NoOpTracingSpec extends AnyFlatSpec with Matchers {

  private val usage = TokenUsage(promptTokens = 12, completionTokens = 8, totalTokens = 20)

  private val toolCall = ToolCall("call-1", "calculator", ujson.Obj("a" -> 1, "b" -> 2))

  /** An agent state with every kind of message in it, as an agent run leaves it. */
  private val agentState = AgentState(
    conversation = Conversation(
      Seq(
        SystemMessage("You are a calculator."),
        UserMessage("What is 1 + 2?"),
        AssistantMessage(None, Seq(toolCall)),
        ToolMessage("""{"result":3}""", "call-1"),
        AssistantMessage("3")
      )
    ),
    tools = ToolRegistry.empty,
    initialQuery = Some("What is 1 + 2?"),
    status = AgentStatus.Complete,
    logs = Vector("[tool] calculator", "[assistant] 3")
  )

  private val completionWithToolCalls = Completion(
    id = "completion-1",
    created = 0L,
    content = "",
    model = "test-model",
    message = AssistantMessage(None, Seq(toolCall)),
    toolCalls = List(toolCall),
    usage = Some(usage.copy(thinkingTokens = Some(4), cachedTokens = Some(2))),
    thinking = Some("add them"),
    estimatedCost = Some(0.0001)
  )

  /** One of every `TraceEvent` case. */
  private val everyEvent: Seq[TraceEvent] = Seq(
    TraceEvent.AgentInitialized("What is 1 + 2?", Vector("calculator")),
    TraceEvent.CompletionReceived("completion-1", "test-model", toolCalls = 1, content = ""),
    TraceEvent.ToolExecuted("calculator", """{"a":1,"b":2}""", "3", duration = 5L, success = true),
    TraceEvent.ToolExecuted("calculator", "{}", "missing argument 'a'", duration = 1L, success = false),
    TraceEvent.ErrorOccurred(new IllegalStateException("boom", new RuntimeException("cause")), "agent step"),
    TraceEvent.TokenUsageRecorded(usage, "test-model", "completion"),
    agentState.toTraceEvent,
    TraceEvent.CustomEvent("custom", ujson.Obj("nested" -> ujson.Arr(1, 2, 3))),
    TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(100, 100), "embed-model", "indexing", inputCount = 4),
    TraceEvent.CostRecorded(0.002, "test-model", "completion", tokenCount = 20, costType = "total"),
    TraceEvent.RAGOperationCompleted("answer", 150L, Some(100), Some(12), Some(8), Some(0.003)),
    TraceEvent.ImageGenerationCompleted("image-model", "provider", "generate", 1, "1024x1024", "hd", 900L),
    TraceEvent.CacheHit(similarity = 0.97, threshold = 0.9),
    TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired)
  )

  /**
   * An exception that fails the test if anything reads it. A tracer that renders the error -
   * as `ConsoleTracing` and `LangfuseTracing` do - would call `getMessage` or `getStackTrace`.
   */
  private class UntouchableException extends RuntimeException {
    override def getMessage: String                      = fail("NoOpTracing read the error's message")
    override def getStackTrace: Array[StackTraceElement] = fail("NoOpTracing read the error's stack trace")
    override def getCause: Throwable                     = fail("NoOpTracing read the error's cause")
    override def toString: String                        = fail("NoOpTracing rendered the error")
  }

  /** Calls every `Tracing` method once, and returns every result. */
  private def callEverything(tracing: Tracing): Seq[Either[Any, Unit]] =
    everyEvent.map(tracing.traceEvent) ++ Seq(
      tracing.traceEvent("plain string event"),
      tracing.traceToolCall("calculator", """{"a":1,"b":2}""", "3"),
      tracing.traceToolCall("", "", ""),
      tracing.traceError(new RuntimeException("no context")),
      tracing.traceError(new IllegalStateException("boom", new RuntimeException("cause")), "agent step"),
      tracing.traceCompletion(completionWithToolCalls, "test-model"),
      tracing.traceTokenUsage(usage, "test-model", "completion"),
      tracing.traceTokenUsage(TokenUsage(0, 0, 0), "test-model", "completion"),
      tracing.traceTokenUsage(TokenUsage(Int.MaxValue, Int.MaxValue, Int.MaxValue), "test-model", "completion"),
      tracing.traceEmbeddingUsage(EmbeddingUsage(100, 100), "embed-model", "query", 1),
      tracing.traceCost(0.002, "test-model", "completion", 20, "total"),
      tracing.traceRAGOperation("search", 12L, embeddingTokens = Some(10))
    )

  /** Runs `body` capturing Scala's stdout and stderr, and every `org.llm4s.trace` log event at any level. */
  private def captureAllOutput(body: => Unit): (String, String, List[ILoggingEvent]) = {
    val out      = new ByteArrayOutputStream()
    val err      = new ByteArrayOutputStream()
    val logger   = LoggerFactory.getLogger("org.llm4s.trace").asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    val thread   = Thread.currentThread.getName
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    val outcome = Try(Console.withOut(out)(Console.withErr(err)(body)))
    logger.detachAppender(appender)
    logger.setLevel(previous)
    outcome.get
    // Other suites may log under org.llm4s.trace on their own threads meanwhile.
    (out.toString, err.toString, appender.list.asScala.toList.filter(_.getThreadName == thread))
  }

  "NoOpTracing" should "accept every TraceEvent variant, including AgentStateUpdated from AgentState#toTraceEvent" in {
    val tracing = new NoOpTracing()

    everyEvent.foreach(event => withClue(event.eventType)(tracing.traceEvent(event) shouldBe Right(())))
    // Every case is represented, so a new TraceEvent case shows up here as a missing type.
    everyEvent.map(_.eventType).distinct should have size 13
  }

  it should "trace agent state as an ordinary event, whatever the state holds" in {
    val tracing = new NoOpTracing()
    val empty   = AgentState(Conversation(Seq.empty), ToolRegistry.empty)
    val failed  = agentState.copy(status = AgentStatus.Failed("tool crashed"))

    tracing.traceEvent(agentState.toTraceEvent) shouldBe Right(())
    tracing.traceEvent(empty.toTraceEvent) shouldBe Right(())
    tracing.traceEvent(failed.toTraceEvent) shouldBe Right(())
  }

  it should "succeed for failed tool calls and empty tool call arguments" in {
    val tracing = new NoOpTracing()

    tracing.traceEvent(TraceEvent.ToolExecuted("calculator", "{}", "error", 1L, success = false)) shouldBe Right(())
    tracing.traceToolCall("", "", "") shouldBe Right(())
  }

  it should "succeed for an error without reading it" in {
    val tracing = new NoOpTracing()
    val error   = new UntouchableException

    tracing.traceError(error) shouldBe Right(())
    tracing.traceError(error, "agent step") shouldBe Right(())
    tracing.traceEvent(TraceEvent.ErrorOccurred(error, "agent step")) shouldBe Right(())
  }

  it should "succeed for completions with tool calls, thinking and no usage" in {
    val tracing = new NoOpTracing()

    tracing.traceCompletion(completionWithToolCalls, "test-model") shouldBe Right(())
    tracing.traceCompletion(completionWithToolCalls.copy(usage = None, thinking = None), "") shouldBe Right(())
  }

  it should "succeed for zero and extreme token usage" in {
    val tracing = new NoOpTracing()

    tracing.traceTokenUsage(TokenUsage(0, 0, 0), "test-model", "completion") shouldBe Right(())
    tracing.traceTokenUsage(TokenUsage(Int.MaxValue, Int.MaxValue, Int.MaxValue), "m", "op") shouldBe Right(())
    tracing.traceTokenUsage(usage.copy(thinkingTokens = Some(1), cacheCreationTokens = Some(3)), "m", "op") shouldBe
      Right(())
  }

  it should "write nothing to stdout, stderr or the log" in {
    val tracing = new NoOpTracing()

    val (out, err, logged) = captureAllOutput {
      callEverything(tracing).foreach(_ shouldBe Right(()))
      tracing.shutdown()
    }

    out shouldBe empty
    err shouldBe empty
    logged shouldBe empty
  }

  it should "keep succeeding over repeated calls" in {
    val tracing = new NoOpTracing()

    (1 to 1000).foreach(i => withClue(s"iteration $i: ")(callEverything(tracing).forall(_ == Right(())) shouldBe true))
  }

  it should "succeed when called from many threads at once" in {
    given ExecutionContext = ExecutionContext.global
    val tracing            = new NoOpTracing()

    val results = Await.result(Future.sequence((1 to 16).map(_ => Future(callEverything(tracing)))), 30.seconds)

    results.flatten.forall(_ == Right(())) shouldBe true
  }

  it should "allow shutdown more than once, and keep succeeding after it" in {
    val tracing = new NoOpTracing()

    noException should be thrownBy {
      tracing.shutdown()
      tracing.shutdown()
    }
    callEverything(tracing).forall(_ == Right(())) shouldBe true
  }
}
