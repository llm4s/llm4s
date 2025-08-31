package org.llm4s.samples.basic

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.trace.{EnhancedTracing, TracingComposer, TraceEvent, TracingMode}
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.error.LLMError
import ujson._
import org.llm4s.config.ConfigReader.LLMConfig

class EnhancedTracingExampleTest extends AnyFunSuite with Matchers {

  test("should create basic enhanced tracing with console mode") {
    val basicTracer = EnhancedTracing.create(TracingMode.Console)(LLMConfig())
    basicTracer shouldBe a[EnhancedTracing]
    
    val agentEvent = TraceEvent.AgentInitialized(
      query = "What's the weather like?",
      tools = Vector("weather", "calculator")
    )
    
    val result = basicTracer.traceEvent(agentEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should compose multiple tracers") {
    val config = LLMConfig()
    val consoleTracer = EnhancedTracing.create(TracingMode.Console)(config)
    val noOpTracer = EnhancedTracing.create(TracingMode.NoOp)(config)
    val composedTracer = TracingComposer.combine(consoleTracer, noOpTracer)
    
    composedTracer shouldBe a[EnhancedTracing]
    
    val agentEvent = TraceEvent.AgentInitialized(
      query = "Test query",
      tools = Vector("test")
    )
    
    val result = composedTracer.traceEvent(agentEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should filter tracing to only error events") {
    val consoleTracer = EnhancedTracing.create(TracingMode.Console)(LLMConfig())
    val errorOnlyTracer = TracingComposer.filter(consoleTracer) { event =>
      event.isInstanceOf[TraceEvent.ErrorOccurred]
    }
    
    val agentEvent = TraceEvent.AgentInitialized(
      query = "This won't be traced",
      tools = Vector("test")
    )
    
    val errorEvent = TraceEvent.ErrorOccurred(
      error = new RuntimeException("Test error"),
      context = "Test context"
    )
    
    // Non-error event should not be traced (filtered out)
    val nonErrorResult = errorOnlyTracer.traceEvent(agentEvent)
    nonErrorResult shouldBe Right(())
    
    // Error event should be traced
    val errorResult = errorOnlyTracer.traceEvent(errorEvent)
    errorResult shouldBe a[Right[_, Unit]]
  }

  test("should transform tracing events with metadata") {
    val consoleTracer = EnhancedTracing.create(TracingMode.Console)(LLMConfig())
    val transformedTracer = TracingComposer.transform(consoleTracer) { event =>
      event match {
        case e: TraceEvent.CustomEvent =>
          TraceEvent.CustomEvent(
            name = s"[ENHANCED] ${e.name}",
            data = ujson.Obj.from(e.data.obj.toSeq :+ ("enhanced" -> true))
          )
        case other => other
      }
    }
    
    val customEvent = TraceEvent.CustomEvent("test", ujson.Obj("value" -> 42))
    val result = transformedTracer.traceEvent(customEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should create tracers for all tracing modes") {
    val config = LLMConfig()
    val modes = Seq(TracingMode.Console, TracingMode.NoOp, TracingMode.Langfuse)
    
    modes.foreach { mode =>
      val tracer = EnhancedTracing.create(mode)(config)
      tracer shouldBe a[EnhancedTracing]
    }
  }

  test("should trace completion events") {
    val tracer = EnhancedTracing.create(TracingMode.Console)(LLMConfig())
    
    val completion = org.llm4s.llmconnect.model.Completion(
      id = "test-id",
      created = System.currentTimeMillis(),
      content = "Test response",
      model = "tracing",
      message = org.llm4s.llmconnect.model.AssistantMessage(
        content = "Test response",
        toolCalls = Vector.empty
      ),
      usage = Some(TokenUsage(10, 20, 30))
    )
    
    val result = tracer.traceCompletion(completion, "test-model")
    result shouldBe a[Right[_, Unit]]
  }

  test("should trace agent state updates") {
    val tracer = EnhancedTracing.create(TracingMode.Console)(LLMConfig())
    
    val agentState = org.llm4s.agent.AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(
        Vector(
          org.llm4s.llmconnect.model.SystemMessage("You are a helpful assistant"),
          org.llm4s.llmconnect.model.UserMessage("Hello")
        )
      ),
      tools = new org.llm4s.toolapi.ToolRegistry(Vector.empty),
      userQuery = "Test query",
      status = org.llm4s.agent.AgentStatus.InProgress,
      logs = Vector("Log entry 1", "Log entry 2")
    )
    
    val result = tracer.traceAgentState(agentState)
    result shouldBe a[Right[_, Unit]]
  }

  test("should handle complex composition of tracers") {
    val config = LLMConfig()
    val consoleTracer = EnhancedTracing.create(TracingMode.Console)(config)
    val noOpTracer = EnhancedTracing.create(TracingMode.NoOp)(config)
    
    val complexTracer = TracingComposer.combine(
      consoleTracer,
      TracingComposer.filter(noOpTracer) { _.isInstanceOf[TraceEvent.CompletionReceived] },
      TracingComposer.transform(consoleTracer) {
        case e: TraceEvent.TokenUsageRecorded =>
          TraceEvent.TokenUsageRecorded(
            usage = e.usage,
            model = s"[COST] ${e.model}",
            operation = e.operation
          )
        case other => other
      }
    )
    
    val tokenEvent = TraceEvent.TokenUsageRecorded(
      usage = TokenUsage(10, 20, 30),
      model = "gpt-4",
      operation = "completion"
    )
    
    val result = complexTracer.traceEvent(tokenEvent)
    result shouldBe a[Right[_, Unit]]
  }

  test("should validate that all event types are properly handled") {
    val tracer = EnhancedTracing.create(TracingMode.Console)(LLMConfig())
    
    // Test all event types
    val events = Seq(
      TraceEvent.AgentInitialized("test", Vector("tool")),
      TraceEvent.CompletionReceived("id", "model", 0, "content"),
      TraceEvent.ToolExecuted("tool", "input", "output", 100, true),
      TraceEvent.ErrorOccurred(new RuntimeException("error"), "context"),
      TraceEvent.TokenUsageRecorded(TokenUsage(1, 2, 3), "model", "op"),
      TraceEvent.AgentStateUpdated("status", 5, 3),
      TraceEvent.CustomEvent("name", ujson.Obj("key" -> "value"))
    )
    
    events.foreach { event =>
      val result = tracer.traceEvent(event)
      result shouldBe a[Right[_, Unit]]
    }
  }

  test("should test tracing mode factory methods") {
    val config = LLMConfig()
    // Test all factory methods
    val consoleTracer1 = EnhancedTracing.create("console")(config)
    val noOpTracer1 = EnhancedTracing.create("noop")(config)
    val langfuseTracer1 = EnhancedTracing.create("langfuse")(config)
    val defaultTracer = EnhancedTracing.create()(config)
    
    // IMPORTANT: All should be instances of EnhancedTracing
    Seq(consoleTracer1, noOpTracer1, langfuseTracer1, defaultTracer).foreach { tracer =>
      tracer shouldBe a[EnhancedTracing]
    }
  }
}
