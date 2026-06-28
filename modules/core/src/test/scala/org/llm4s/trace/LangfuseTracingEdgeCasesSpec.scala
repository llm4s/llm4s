package org.llm4s.trace

import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LangfuseTracingEdgeCasesSpec extends AnyFlatSpec with Matchers {

  private def makeTracing(mockClient: MockHttpClient) = new LangfuseTracing(
    langfuseUrl = "https://cloud.langfuse.com",
    publicKey = "pk-test",
    secretKey = "sk-test",
    environment = "test",
    release = "v1.0.0",
    version = "1.0.0",
    httpClient = mockClient,
    restoreInterrupt = () => ()
  )

  // =========================================================================
  // traceEvent - AgentInitialized
  // =========================================================================

  "LangfuseTracing" should "handle AgentInitialized event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.AgentInitialized("test query", Vector("tool1", "tool2")))

    result.isRight shouldBe true
    mock.postCallCount shouldBe 1
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "trace-create"
    event("body")("input").str shouldBe "test query"
    event("body")("name").str shouldBe "LLM4S Agent Run"
    event("body")("environment").str shouldBe "test"
    event("body")("release").str shouldBe "v1.0.0"
  }

  // =========================================================================
  // traceEvent - CompletionReceived
  // =========================================================================

  it should "handle CompletionReceived event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.CompletionReceived("comp-1", "gpt-4", 2, "Hello world"))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "generation-create"
    event("body")("model").str shouldBe "gpt-4"
    event("body")("metadata")("completion_id").str shouldBe "comp-1"
  }

  // =========================================================================
  // traceEvent - ErrorOccurred
  // =========================================================================

  it should "handle ErrorOccurred event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.ErrorOccurred(new RuntimeException("test error"), "test-context"))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "event-create"
    event("body")("level").str shouldBe "ERROR"
    event("body")("statusMessage").str should include("test error")
    event("body")("metadata")("context").str shouldBe "test-context"
    event("body")("metadata")("error_type").str shouldBe "RuntimeException"
  }

  // =========================================================================
  // traceEvent - TokenUsageRecorded
  // =========================================================================

  it should "handle TokenUsageRecorded event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.TokenUsageRecorded(TokenUsage(100, 50, 150), "gpt-4", "completion"))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "event-create"
    event("body")("name").str should include("Token Usage")
    event("body")("metadata")("model").str shouldBe "gpt-4"
    event("body")("metadata")("operation").str shouldBe "completion"
  }

  // =========================================================================
  // traceEvent - AgentStateUpdated
  // =========================================================================

  it should "handle AgentStateUpdated event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.AgentStateUpdated("InProgress", 5, 3))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "trace-create"
    event("body")("metadata")("status").str shouldBe "InProgress"
  }

  // =========================================================================
  // traceEvent - CustomEvent
  // =========================================================================

  it should "handle CustomEvent" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.CustomEvent("my-event", ujson.Obj("key" -> "value")))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "event-create"
    event("body")("name").str shouldBe "my-event"
    event("body")("metadata")("source").str shouldBe "custom_event"
  }

  // =========================================================================
  // traceEvent - EmbeddingUsageRecorded
  // =========================================================================

  it should "handle EmbeddingUsageRecorded event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(
      TraceEvent.EmbeddingUsageRecorded(EmbeddingUsage(500, 500), "text-embedding-3-small", "indexing", 10)
    )

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "event-create"
    event("body")("name").str should include("Embedding Usage")
    event("body")("metadata")("model").str shouldBe "text-embedding-3-small"
    event("body")("metadata")("operation").str shouldBe "indexing"
  }

  // =========================================================================
  // traceEvent - CostRecorded
  // =========================================================================

  it should "handle CostRecorded event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.CostRecorded(0.005, "gpt-4", "completion", 1000, "total"))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "event-create"
    event("body")("name").str should include("Cost")
    event("body")("metadata")("cost_usd").num shouldBe 0.005
    event("body")("metadata")("cost_type").str shouldBe "total"
  }

  // =========================================================================
  // traceEvent - CacheHit
  // =========================================================================

  it should "handle CacheHit event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.CacheHit(0.95, 0.85))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "span-create"
    event("body")("name").str shouldBe "Cache Hit"
    event("body")("input")("similarity").num shouldBe 0.95
    event("body")("input")("threshold").num shouldBe 0.85
  }

  // =========================================================================
  // traceEvent - CacheMiss
  // =========================================================================

  it should "handle CacheMiss event" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.CacheMiss(TraceEvent.CacheMissReason.TtlExpired))

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "span-create"
    event("body")("name").str shouldBe "Cache Miss"
    event("body")("input")("reason").str shouldBe "ttl_expired"
  }

  // =========================================================================
  // traceEvent - RAGOperationCompleted with all optional fields
  // =========================================================================

  it should "handle RAGOperationCompleted with all optional fields" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(
      TraceEvent.RAGOperationCompleted("search", 200L, Some(100), Some(200), Some(50), Some(0.003))
    )

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "span-create"
    event("body")("name").str shouldBe "RAG search"
    event("body")("output")("embedding_tokens").num.toInt shouldBe 100
    event("body")("output")("llm_prompt_tokens").num.toInt shouldBe 200
    event("body")("output")("llm_completion_tokens").num.toInt shouldBe 50
    event("body")("output")("total_cost_usd").num shouldBe 0.003
  }

  it should "handle RAGOperationCompleted without optional fields" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val result = tracing.traceEvent(TraceEvent.RAGOperationCompleted("index", 500L))

    result.isRight shouldBe true
    val body   = ujson.read(mock.lastBody.get)
    val output = body("batch")(0)("body")("output")
    output.obj.contains("embedding_tokens") shouldBe false
    output.obj.contains("total_cost_usd") shouldBe false
  }

  // =========================================================================
  // traceAgentState - with conversation messages (hierarchical trace)
  // =========================================================================

  it should "create hierarchical trace for agent state with messages" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val state = AgentState(
      conversation = Conversation(
        Seq(
          SystemMessage("You are helpful"),
          UserMessage("Hello"),
          AssistantMessage(Some("Hi there!"), Seq.empty)
        )
      ),
      tools = ToolRegistry.empty,
      status = AgentStatus.Complete,
      logs = Vector("log1")
    )

    val result = tracing.traceAgentState(state)

    result.isRight shouldBe true
    mock.postCallCount shouldBe 1

    val body  = ujson.read(mock.lastBody.get)
    val batch = body("batch").arr
    // 1 main trace + 3 child spans (one per message)
    batch should have size 4

    // Main trace
    batch(0)("type").str shouldBe "trace-create"
    batch(0)("body")("input").str shouldBe "Hello"
    batch(0)("body")("output").str shouldBe "Hi there!"

    // Child spans
    batch(1)("type").str shouldBe "span-create"
    batch(1)("body")("name").str should include("System Message")

    batch(2)("type").str shouldBe "span-create"
    batch(2)("body")("name").str should include("User Input")

    batch(3)("type").str shouldBe "span-create"
    batch(3)("body")("name").str should include("LLM Generation")
  }

  it should "return Right without sending batch for empty conversation" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val state = AgentState(
      conversation = Conversation(Seq.empty),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress
    )

    val result = tracing.traceAgentState(state)

    result.isRight shouldBe true
    mock.postCallCount shouldBe 0 // No batch sent for empty conversation
  }

  // =========================================================================
  // traceCompletion via LangfuseTracing
  // =========================================================================

  it should "handle traceCompletion correctly" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val completion = Completion(
      id = "comp-test",
      created = 0L,
      content = "test response",
      model = "gpt-4",
      message = AssistantMessage(Some("test response"), Seq.empty)
    )

    val result = tracing.traceCompletion(completion, "gpt-4")

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("type").str shouldBe "generation-create"
    event("body")("model").str shouldBe "gpt-4"
    event("body")("output")("content").str shouldBe "test response"
  }

  it should "handle traceCompletion with tool calls" in {
    val mock    = new MockHttpClient(HttpResponse(200, ""))
    val tracing = makeTracing(mock)

    val toolCall = ToolCall("call-1", "calculator", ujson.Obj("a" -> 1))
    val completion = Completion(
      id = "comp-tools",
      created = 0L,
      content = "Let me calculate",
      model = "gpt-4",
      message = AssistantMessage(Some("Let me calculate"), Seq(toolCall))
    )

    val result = tracing.traceCompletion(completion, "gpt-4")

    result.isRight shouldBe true
    val body  = ujson.read(mock.lastBody.get)
    val event = body("batch")(0)
    event("body")("metadata")("tool_calls").num.toInt shouldBe 1
  }

  // =========================================================================
  // LangfuseTracing.from factory
  // =========================================================================

  "LangfuseTracing.from" should "create instance from LangfuseConfig" in {
    val config = LangfuseConfig(
      url = "https://custom.langfuse.com",
      publicKey = Some("pk-test"),
      secretKey = Some("sk-test"),
      env = "production",
      release = "2.0.0",
      version = "2.0.0"
    )

    val tracing = LangfuseTracing.from(config)
    tracing shouldBe a[LangfuseTracing]
  }

  it should "handle config with empty keys" in {
    val config  = LangfuseConfig()
    val tracing = LangfuseTracing.from(config)
    // With empty keys, tracing should still be created (will skip exports)
    tracing shouldBe a[LangfuseTracing]
    // Verify it returns Right (skips export) when keys are empty
    tracing.traceEvent(TraceEvent.CustomEvent("test", ujson.Obj())).isRight shouldBe true
  }
}
