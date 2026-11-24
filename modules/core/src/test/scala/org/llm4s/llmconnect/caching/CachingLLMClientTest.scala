package org.llm4s.llmconnect.caching

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result
import org.llm4s.trace.{ TraceEvent, Tracing }

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
  }

  class MockClock extends Clock {
    var currentTime: Instant                   = Instant.parse("2024-01-01T10:00:00Z")
    override def getZone: ZoneId               = ZoneId.of("UTC")
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = currentTime
  }

  class MockLLMClient extends LLMClient {
    var callCount = 0
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount += 1
      Right(
        Completion(
          id = "comp-123",
          created = Instant.now(),
          model = "gpt-model",
          message = AssistantMessage("Response from LLM"),
          usage = None
        )
      )
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      Left(org.llm4s.error.ProcessingError("mock", "streamComplete not implemented in mock"))
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 100
    override def validate(): Result[Unit]    = Right(())
    override def close(): Unit               = ()
  }

  class MockEmbeddingClient(responses: Map[String, Seq[Double]]) extends EmbeddingProvider {
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

  test("A conversation with an image bypasses the cache: its prompt is embedded as text") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, new MockClock())
    val image = ContentPart
      .image(
        org.llm4s.media.MediaSource.Url(java.net.URI.create("https://example.com/a.png")),
        org.llm4s.media.MediaType.Png
      )
      .getOrElse(fail())
    val withImage = Conversation(Seq(UserMessage(Vector(ContentPart.Text("Hello"), image))))

    client.complete(withImage)
    client.complete(withImage)
    mockLLM.callCount shouldBe 2
    tracing.events shouldBe empty
  }

  test("A conversation with a null part goes to the base client, never throws") {
    val mockLLM = new MockLLMClient()
    val client =
      new CachingLLMClient(
        mockLLM,
        new MockEmbeddingClient(Map.empty),
        embeddingModel,
        config,
        new MockTracing(),
        new MockClock()
      )
    noException should be thrownBy client.complete(Conversation(Seq(UserMessage(null: Vector[ContentPart]))))
    mockLLM.callCount shouldBe 1
  }

  test("Cache miss on options mismatch logs OptionsMismatch") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, new MockClock())

    val conv = Conversation.userOnly("Hello").getOrElse(fail())

    // Request 1: temp 0.7
    client.complete(conv, CompletionOptions(temperature = Some(0.7)))
    mockLLM.callCount shouldBe 1

    // Request 2: temp 0.0 (Should be miss despite similar embedding)
    client.complete(conv, CompletionOptions(temperature = Some(0.0)))
    mockLLM.callCount shouldBe 2

    // Verify reason
    tracing.events.last match {
      case missEvent: TraceEvent.CacheMiss =>
        missEvent.reason shouldBe TraceEvent.CacheMissReason.OptionsMismatch
      case other => fail(s"Expected CacheMiss but got $other")
    }
  }

  test("A different thinking or reasoning setting misses as OptionsMismatch; the same one hits") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, new MockClock())
    val conv      = Conversation.userOnly("Hello").getOrElse(fail())
    val adaptive  = CompletionOptions().withThinking(Thinking.Adaptive).withReasoning(ReasoningEffort.Low)

    client.complete(conv, adaptive)
    client.complete(conv, adaptive)
    mockLLM.callCount shouldBe 1
    tracing.events.last shouldBe a[TraceEvent.CacheHit]

    Seq(adaptive.withThinking(Thinking.Off), adaptive.withReasoning(ReasoningEffort.High)).foreach { other =>
      client.complete(conv, other)
      tracing.events.last match {
        case miss: TraceEvent.CacheMiss => miss.reason shouldBe TraceEvent.CacheMissReason.OptionsMismatch
        case event                      => fail(s"Expected CacheMiss but got $event")
      }
    }
    mockLLM.callCount shouldBe 3
  }

  test("A request that differs only in promptCache hits the answer cached without it") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("Hello" -> Seq(1.0, 0.0, 0.0)))
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, new MockClock())
    val conv      = Conversation.userOnly("Hello").getOrElse(fail())

    client.complete(conv, CompletionOptions())
    client.complete(conv, CompletionOptions().withPromptCache(PromptCache.default))
    mockLLM.callCount shouldBe 1
    tracing.events.last shouldBe a[TraceEvent.CacheHit]
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

  test("CacheConfig setters check the new value") {
    val base = CacheConfig.create(0.5, 1.hour).getOrElse(fail())
    base.withMaxSize(10).map(_.maxSize) shouldBe Right(10)
    base.withTtl(2.hours).map(_.ttl) shouldBe Right(2.hours)
    base.withSimilarityThreshold(0.8).map(_.similarityThreshold) shouldBe Right(0.8)
    base.withMaxSize(-1).isLeft shouldBe true
    base.withTtl(0.seconds).isLeft shouldBe true
    base.withSimilarityThreshold(Double.NaN).isLeft shouldBe true
  }

  test("A tool loop bypasses the cache: step 2 is never served step 1's tool-call completion") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map("weather" -> Seq(1.0, 0.0, 0.0)))
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, new MockTracing(), new MockClock())

    val step1 = Conversation(Seq(UserMessage("What is the weather?")))
    val step2 = Conversation(
      Seq(
        UserMessage("What is the weather?"),
        AssistantMessage("Checking...", toolCalls = List(ToolCall("1", "weather", "{\"loc\":\"Paris\"}"))),
        ToolMessage("Paris: 20C", "1")
      )
    )

    client.complete(step1)
    mockLLM.callCount shouldBe 1
    client.complete(step2)
    mockLLM.callCount shouldBe 2
  }

  test("A conversation with tool turns is neither embedded nor stored") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map.empty)
    val tracing   = new MockTracing()
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, tracing, new MockClock())

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
    client.complete(conversation)

    mockLLM.callCount shouldBe 2
    mockEmbed.lastInput shouldBe None
    tracing.events shouldBe empty
  }

  test("Security: Prompt text excludes assistant messages") {
    val mockLLM   = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map.empty)
    val client    = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config, new MockTracing(), new MockClock())

    val conversation = Conversation(
      messages = List(
        UserMessage("What is the weather?"),
        AssistantMessage("It is 20C in Paris"),
        UserMessage("And in London?")
      )
    )

    client.complete(conversation)

    mockEmbed.lastInput shouldBe Some("user: What is the weather?\nuser: And in London?")
  }
}
