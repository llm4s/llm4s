package org.llm4s.llmconnect.caching

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.TokenUsage

import java.time.{ Clock, Instant, ZoneId }
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer

class CachingLLMClientTest extends AnyFunSuite with Matchers {

  class MockTracing extends Tracing {
    val events = ListBuffer[TraceEvent]()
    override def traceEvent(event: TraceEvent): Result[Unit] = {
      events += event
      Right(())
    }
    override def traceAgentState(state: AgentState): Result[Unit]                                   = Right(())
    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    override def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    override def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  class MockClock extends Clock {
    var currentTime: Instant                   = Instant.parse("2024-01-01T10:00:00Z")
    override def getZone: ZoneId               = ZoneId.of("UTC")
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = currentTime
  }

  class MockProvider extends EmbeddingProvider {
    def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = Left(
      org.llm4s.error.ProcessingError("mock", "Mock provider not implemented")
    )
  }

  class MockLLMClient extends LLMClient {
    var callCount = 0
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount += 1
      Right(
        Completion(
          id = "comp-123",
          created = Instant.now().toEpochMilli,
          content = "Response from LLM",
          model = "gpt-model",
          message = AssistantMessage("Response from LLM"),
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      Left(org.llm4s.error.ProcessingError("mock", "streamComplete not implemented in mock"))
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 100
    override def validate(): Result[Unit]    = Right(())
    override def close(): Unit               = ()
  }

  class MockEmbeddingClient(responses: Map[String, Seq[Double]])
      extends EmbeddingClient(new MockProvider(), None, "embedding") {
    var lastInput: Option[String] = None
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val text = request.input.head
      lastInput = Some(text)
      val embedding = responses
        .find { case (k, _) => text.contains(k) }
        .map(_._2)
        .getOrElse(Seq.fill(3)(0.0))

      Right(
        EmbeddingResponse(
          embeddings = Seq(embedding),
          metadata = Map.empty
        )
      )
    }
  }

  val embeddingModel = EmbeddingModelConfig("test-embedding", 3)
  val config         = CacheConfig.create(similarityThreshold = 0.9, ttl = 1.hour).getOrElse(fail("Invalid config"))

  test("Cache miss calls base client, caches result, and logs TraceEvent with correct reason") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val tracing   = new MockTracing()
    val clock     = new MockClock()

    val client = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, clock)

    val conversation = Conversation.userOnly("Hello").getOrElse(fail())

    // Miss (Empty Cache)
    client.complete(conversation)
    mockLLM.callCount shouldBe 1
    tracing.events should have size 1
    tracing.events.head match {
      case missEvent: TraceEvent.CacheMiss =>
        missEvent.reason shouldBe TraceEvent.CacheMissReason.LowSimilarity
      case other => fail(s"Expected CacheMiss but got $other")
    }

    // Hit
    client.complete(conversation)
    mockLLM.callCount shouldBe 1
    tracing.events should have size 2
    tracing.events.last shouldBe a[TraceEvent.CacheHit]
  }

  test("Cache miss on options mismatch logs OptionsMismatch") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, new MockClock())

    val conv = Conversation.userOnly("Hello").getOrElse(fail())

    // Request 1: temp 0.7
    client.complete(conv, CompletionOptions(temperature = 0.7))
    mockLLM.callCount shouldBe 1

    // Request 2: temp 0.0 (Should be miss despite similar embedding)
    client.complete(conv, CompletionOptions(temperature = 0.0))
    mockLLM.callCount shouldBe 2

    // Verify reason
    tracing.events.last match {
      case missEvent: TraceEvent.CacheMiss =>
        missEvent.reason shouldBe TraceEvent.CacheMissReason.OptionsMismatch
      case other => fail(s"Expected CacheMiss but got $other")
    }
  }

  test("TTL expiration logs TtlExpired") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val clock     = new MockClock()
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, clock)

    val conv = Conversation.userOnly("Hello").getOrElse(fail())

    // Initial call
    client.complete(conv)
    mockLLM.callCount shouldBe 1

    // Advance time past TTL
    clock.currentTime = clock.currentTime.plus(2, java.time.temporal.ChronoUnit.HOURS)

    // Should miss/expire
    client.complete(conv)
    mockLLM.callCount shouldBe 2

    // Verify reason
    tracing.events.last match {
      case missEvent: TraceEvent.CacheMiss =>
        missEvent.reason shouldBe TraceEvent.CacheMissReason.TtlExpired
      case other => fail(s"Expected CacheMiss but got $other")
    }
  }

  test("Config validation returns Result") {
    CacheConfig.create(1.1, 1.hour).isLeft shouldBe true
    CacheConfig.create(-0.1, 1.hour).isLeft shouldBe true
    CacheConfig.create(0.5, 0.seconds).isLeft shouldBe true
    CacheConfig.create(0.5, 1.hour, 0).isLeft shouldBe true
    CacheConfig.create(0.5, 1.hour, 100).isRight shouldBe true
  }

  test("Security: Prompt text excludes sensitive tool outputs") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map.empty)
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, new MockTracing(), new MockClock())

    val conversation = Conversation(
      messages = List(
        UserMessage("What is the weather?"),
        AssistantMessage("Checking...", toolCalls = List(ToolCall("1", "weather", "{\"loc\":\"Paris\"}"))),
        ToolMessage("Paris: 20C", "1"),
        AssistantMessage("It is 20C"),
        UserMessage("And in London?")
      )
    )

    client.complete(conversation)

    // Verify embedding input only contains User messages
    val expectedInput = "user: What is the weather?\nuser: And in London?"
    mockEmbed.lastInput shouldBe Some(expectedInput)
  }
}
