package org.llm4s.llmconnect.middleware

import org.llm4s.agent.AgentState
import org.llm4s.error.{ NetworkError, RateLimitError }
import org.llm4s.llmconnect.caching.{ CacheConfig, CachingLLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.metrics.{ MetricsCollector, Outcome }
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.{ ArrayBuffer, ListBuffer }
import scala.concurrent.duration._

/**
 * Integration tests for middleware stack composition.
 *
 * Covers critical behaviours like cache-hit short-circuiting, metrics recording
 * through multiple layers, rate limiting, input sanitization, error propagation,
 * and streaming passthrough.
 */
class MiddlewareStackIntegrationSpec extends AnyFlatSpec with Matchers {

  // ---------------------------------------------------------------------------
  // Test helpers shared across all tests
  // ---------------------------------------------------------------------------

  import TestHelpers.FakeLogger

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // Tracks how many times the underlying LLM was actually invoked.
  class CountingMockClient(response: String = "Mock Response") extends LLMClient {
    val callCount                              = new java.util.concurrent.atomic.AtomicInteger(0)
    var lastConversation: Option[Conversation] = None
    val chunks: ArrayBuffer[String]            = ArrayBuffer.empty

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount.incrementAndGet()
      lastConversation = Some(conversation)
      Right(
        Completion(
          id = s"id-${callCount.get()}",
          created = System.currentTimeMillis(),
          content = response,
          model = "test-model",
          message = AssistantMessage(response),
          usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      callCount.incrementAndGet()
      lastConversation = Some(conversation)
      val words = response.split(" ")
      words.foreach { word =>
        val chunk = StreamedChunk("stream-id", Some(word + " "), None, None, None)
        chunks += (word + " ")
        onChunk(chunk)
      }
      onChunk(StreamedChunk("stream-id", None, None, Some("stop"), None))
      Right(
        Completion(
          id = "stream-id",
          created = System.currentTimeMillis(),
          content = response,
          model = "test-model",
          message = AssistantMessage(response),
          usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
        )
      )
    }

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // Always returns a NetworkError.
  class NetworkErrorMockClient extends LLMClient {
    val callCount = new java.util.concurrent.atomic.AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount.incrementAndGet()
      Left(NetworkError("Simulated network failure", None, "mock://error"))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      callCount.incrementAndGet()
      Left(NetworkError("Simulated network failure", None, "mock://error"))
    }

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // In-memory MetricsCollector that counts invocations.
  class CountingMetricsCollector extends MetricsCollector {
    val requestCount                                           = new java.util.concurrent.atomic.AtomicInteger(0)
    val tokenCount                                             = new java.util.concurrent.atomic.AtomicInteger(0)
    val outcomes: ArrayBuffer[Outcome]                         = ArrayBuffer.empty
    val requestEntries: ArrayBuffer[(String, String, Outcome)] = ArrayBuffer.empty

    override def observeRequest(
      provider: String,
      model: String,
      outcome: Outcome,
      duration: FiniteDuration
    ): Unit = {
      requestCount.incrementAndGet()
      outcomes.synchronized(outcomes += outcome)
      requestEntries.synchronized(requestEntries += ((provider, model, outcome)))
    }

    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit =
      tokenCount.addAndGet((inputTokens + outputTokens).toInt)

    override def recordCost(provider: String, model: String, costUsd: Double): Unit = ()

    def successCount: Int = outcomes.count(_ == Outcome.Success)
    def errorCount: Int   = outcomes.count { case Outcome.Error(_) => true; case _ => false }
  }

  // No-op Tracing implementation for caching tests.
  class NoOpTracing extends Tracing {
    val events: ListBuffer[TraceEvent] = ListBuffer.empty

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

  // EmbeddingProvider that returns identical unit vectors so cosine similarity = 1.0.
  class SameVectorEmbeddingProvider(dimensions: Int = 3) extends EmbeddingProvider {
    var callCount: Int = 0

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      callCount += 1
      val vectors = request.input.map(_ => Seq.fill(dimensions)(1.0))
      Right(EmbeddingResponse(embeddings = vectors, metadata = Map.empty))
    }
  }

  // ---------------------------------------------------------------------------
  // Test 1: MetricsMiddleware + LoggingMiddleware + MockLLMClient stack
  // ---------------------------------------------------------------------------

  "MetricsMiddleware → LoggingMiddleware → MockLLMClient stack" should
    "record metrics, emit logs, and return the correct response on the first call" in {
      val baseMock          = new CountingMockClient("Hello from LLM")
      val logger            = new FakeLogger()
      val metrics           = new CountingMetricsCollector()
      val metricsMiddleware = new MetricsMiddleware(metrics, "test-provider", "test-model")
      val loggingMiddleware = new LoggingMiddleware(logger = logger)

      val client = LLMClientPipeline(baseMock)
        .use(loggingMiddleware) // innermost
        .use(metricsMiddleware) // outermost
        .build()

      val conversation = Conversation(Seq(UserMessage("What is Scala?")))
      val result       = client.complete(conversation)

      result.isRight shouldBe true
      result.map(_.content) shouldBe Right("Hello from LLM")
      baseMock.callCount.get() shouldBe 1
      metrics.requestCount.get() shouldBe 1
      metrics.successCount shouldBe 1
      logger.debugs.exists(_.contains("Request:")) shouldBe true
      logger.debugs.exists(_.contains("Success")) shouldBe true
    }

  // ---------------------------------------------------------------------------
  // Test 2: Full stack with semantic caching — cache-hit short-circuits mock
  // ---------------------------------------------------------------------------

  "MetricsMiddleware + CachingLLMClient + LoggingMiddleware stack" should
    "call the base mock only once for identical queries (cache hit on second call)" in {
      val baseMock        = new CountingMockClient("Cached Response")
      val logger          = new FakeLogger()
      val metrics         = new CountingMetricsCollector()
      val tracing         = new NoOpTracing()
      val embedProvider   = new SameVectorEmbeddingProvider(dimensions = 3)
      val embeddingClient = new EmbeddingClient(embedProvider, None, "embedding")
      val embeddingModel  = EmbeddingModelConfig("test-embedding", 3)
      val cacheConfig = CacheConfig
        .create(
          similarityThreshold = 0.9,
          ttl = 1.hour,
          maxSize = 100
        )
        .getOrElse(fail("Invalid CacheConfig"))

      // Build: LoggingMiddleware → MockLLMClient (innermost layers)
      val loggedBase = LLMClientPipeline(baseMock)
        .use(new LoggingMiddleware(logger = logger))
        .build()

      // Wrap loggedBase in the caching layer
      val cachingClient = new CachingLLMClient(
        baseClient = loggedBase,
        embeddingClient = embeddingClient,
        embeddingModel = embeddingModel,
        config = cacheConfig,
        tracing = tracing
      )

      // Wrap the caching layer with metrics (outermost)
      val client = LLMClientPipeline(cachingClient)
        .use(new MetricsMiddleware(metrics, "test-provider", "test-model"))
        .build()

      val conversation = Conversation(Seq(UserMessage("Tell me about Scala")))

      // First call — cache miss, base mock is invoked
      val result1 = client.complete(conversation)
      result1.isRight shouldBe true
      result1.map(_.content) shouldBe Right("Cached Response")
      baseMock.callCount.get() shouldBe 1

      // Second call — cache hit, base mock must NOT be invoked again
      val result2 = client.complete(conversation)
      result2.isRight shouldBe true
      result2.map(_.content) shouldBe Right("Cached Response")
      baseMock.callCount.get() shouldBe 1 // still 1

      // Metrics are incremented on every call (both hit and miss go through MetricsMiddleware)
      metrics.requestCount.get() shouldBe 2
      metrics.successCount shouldBe 2

      // Logging is emitted on the first (miss) call only
      logger.debugs.size should be >= 1
    }

  // ---------------------------------------------------------------------------
  // Test 3: RateLimitingMiddleware enforces request limits
  // ---------------------------------------------------------------------------

  "RateLimitingMiddleware → MockLLMClient stack" should
    "allow the first request and reject subsequent requests when bucket is empty" in {
      val baseMock = new CountingMockClient()
      // 1 RPM burst 1 — only one token in the bucket
      val middleware = new RateLimitingMiddleware(1, 1)
      val client     = LLMClientPipeline(baseMock).use(middleware).build()

      val conversation = Conversation(Seq(UserMessage("hello")))

      // First request consumes the only token
      val first = client.complete(conversation)
      first.isRight shouldBe true
      baseMock.callCount.get() shouldBe 1

      // Second request must be rejected immediately (bucket is empty)
      val second = client.complete(conversation)
      second.isLeft shouldBe true
      second.swap.getOrElse(fail("Expected Left")).shouldBe(a[RateLimitError])

      // The base mock is never called for the rejected request
      baseMock.callCount.get() shouldBe 1
    }

  "RateLimitingMiddleware → MockLLMClient stack" should
    "allow requests again after the token bucket refills" in {
      val baseMock               = new CountingMockClient()
      val startNs                = System.nanoTime()
      var nowNs                  = startNs
      val timeSource: () => Long = () => nowNs

      // 600 RPM, burst 1 → refills ~1 token per 100ms
      val middleware = new RateLimitingMiddleware(600, 1, timeSource)
      val client     = LLMClientPipeline(baseMock).use(middleware).build()

      val conv = Conversation(Seq(UserMessage("ping")))

      // Consume the single token
      client.complete(conv).isRight shouldBe true

      // Immediately, bucket is empty
      client.complete(conv).isLeft shouldBe true

      // Advance time by 150ms
      nowNs = startNs + 150_000_000L

      // After refill, request should succeed
      val refilled = client.complete(conv)
      refilled.isRight shouldBe true
      baseMock.callCount.get() shouldBe 2
    }

  // ---------------------------------------------------------------------------
  // Test 4: InputSanitizationMiddleware filters PII-containing prompts
  // ---------------------------------------------------------------------------

  "InputSanitizationMiddleware → MockLLMClient stack" should
    "block prompts containing forbidden PII patterns before they reach the mock" in {
      val baseMock = new CountingMockClient()
      // Forbid social security number patterns
      val ssnPattern = "\\d{3}-\\d{2}-\\d{4}".r
      val middleware = new InputSanitizationMiddleware(
        forbiddenPatterns = Seq(ssnPattern)
      )
      val client = LLMClientPipeline(baseMock).use(middleware).build()

      val piiConversation = Conversation(Seq(UserMessage("My SSN is 123-45-6789, please process it.")))
      val result          = client.complete(piiConversation)

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected Left")).shouldBe(a[org.llm4s.error.InvalidInputError])
      // Base client must not be called when input is rejected
      baseMock.callCount.get() shouldBe 0
    }

  "InputSanitizationMiddleware → MockLLMClient stack" should
    "pass clean prompts through to the base client unchanged" in {
      val baseMock   = new CountingMockClient("OK")
      val middleware = new InputSanitizationMiddleware(maxTotalCharacters = 1000)
      val client     = LLMClientPipeline(baseMock).use(middleware).build()

      val conv   = Conversation(Seq(UserMessage("What is functional programming?")))
      val result = client.complete(conv)

      result.isRight shouldBe true
      result.map(_.content) shouldBe Right("OK")
      baseMock.callCount.get() shouldBe 1
    }

  // ---------------------------------------------------------------------------
  // Test 5: Error propagation through full MetricsMiddleware + LoggingMiddleware stack
  // ---------------------------------------------------------------------------

  "MetricsMiddleware → LoggingMiddleware → NetworkErrorMockClient stack" should
    "propagate NetworkError unchanged to the caller while recording metrics and logging the failure" in {
      val baseMock = new NetworkErrorMockClient()
      val logger   = new FakeLogger()
      val metrics  = new CountingMetricsCollector()

      val client = LLMClientPipeline(baseMock)
        .use(new LoggingMiddleware(logger = logger))
        .use(new MetricsMiddleware(metrics, "test-provider", "test-model"))
        .build()

      val conversation = Conversation(Seq(UserMessage("Trigger a network error")))
      val result       = client.complete(conversation)

      // Error must reach the caller unchanged
      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected Left")).shouldBe(a[NetworkError])

      // Metrics middleware must record the error
      metrics.requestCount.get() shouldBe 1
      metrics.errorCount shouldBe 1
      metrics.successCount shouldBe 0

      // Logging middleware must warn about the failure
      logger.warns.exists(_.contains("Failed")) shouldBe true

      // The underlying mock was still called exactly once
      baseMock.callCount.get() shouldBe 1
    }

  "MetricsMiddleware → LoggingMiddleware → NetworkErrorMockClient stack" should
    "record the correct ErrorKind for a NetworkError in metrics" in {
      val baseMock = new NetworkErrorMockClient()
      val metrics  = new CountingMetricsCollector()

      val client = LLMClientPipeline(baseMock)
        .use(new LoggingMiddleware())
        .use(new MetricsMiddleware(metrics, "p", "m"))
        .build()

      client.complete(Conversation(Seq.empty))

      metrics.requestEntries should have size 1
      metrics.requestEntries.head match {
        case (provider, model, Outcome.Error(org.llm4s.metrics.ErrorKind.Network)) =>
          provider shouldBe "p"
          model shouldBe "m"
        case other => fail(s"Unexpected entry: $other")
      }
    }

  // ---------------------------------------------------------------------------
  // Test 6: Streaming passthrough through a multi-layer stack
  // ---------------------------------------------------------------------------

  "LoggingMiddleware → MetricsMiddleware → MockLLMClient stack" should
    "emit all streamed chunks through multiple middleware layers" in {
      val baseMock = new CountingMockClient("hello world")
      val logger   = new FakeLogger()
      val metrics  = new CountingMetricsCollector()

      val client = LLMClientPipeline(baseMock)
        .use(new MetricsMiddleware(metrics, "p", "m")) // innermost
        .use(new LoggingMiddleware(logger = logger))   // outermost
        .build()

      val conversation   = Conversation(Seq(UserMessage("Stream this")))
      val receivedChunks = ArrayBuffer[StreamedChunk]()

      val result = client.streamComplete(conversation, CompletionOptions(), chunk => receivedChunks += chunk)

      result.isRight shouldBe true
      result.map(_.content) shouldBe Right("hello world")

      // Chunks should have been forwarded to our collector
      receivedChunks.nonEmpty shouldBe true

      // Metrics and logging must still fire for streaming
      metrics.requestCount.get() shouldBe 1
      metrics.successCount shouldBe 1
      logger.debugs.exists(_.contains("STREAM")) shouldBe true
    }

  "InputSanitizationMiddleware → MockLLMClient stack" should
    "block oversized prompts in streamComplete before reaching the base client" in {
      val baseMock   = new CountingMockClient()
      val middleware = new InputSanitizationMiddleware(maxTotalCharacters = 10)
      val client     = LLMClientPipeline(baseMock).use(middleware).build()

      val bigConv = Conversation(Seq(UserMessage("This is a very long prompt that exceeds the limit")))
      val chunks  = ArrayBuffer[StreamedChunk]()
      val result  = client.streamComplete(bigConv, CompletionOptions(), chunk => chunks += chunk)

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected Left")).shouldBe(a[org.llm4s.error.InvalidInputError])
      chunks.isEmpty shouldBe true
      baseMock.callCount.get() shouldBe 0
    }

  // ---------------------------------------------------------------------------
  // Test 7: RateLimitingMiddleware inside a wider stack with Metrics + Logging
  // ---------------------------------------------------------------------------

  "MetricsMiddleware → RateLimitingMiddleware → LoggingMiddleware → MockLLMClient" should
    "record a RateLimit error in metrics when the bucket is exhausted" in {
      val baseMock = new CountingMockClient()
      val metrics  = new CountingMetricsCollector()
      val logger   = new FakeLogger()

      val client = LLMClientPipeline(baseMock)
        .use(new LoggingMiddleware(logger = logger))   // innermost
        .use(new RateLimitingMiddleware(1, 1))         // rate limiter
        .use(new MetricsMiddleware(metrics, "p", "m")) // outermost
        .build()

      val conv = Conversation(Seq(UserMessage("first")))

      // First: succeeds
      client.complete(conv).isRight shouldBe true

      // Second: rate limited
      val rejected = client.complete(conv)
      rejected.isLeft shouldBe true
      rejected.swap.getOrElse(fail("Expected Left")).shouldBe(a[RateLimitError])

      // Two requests recorded by MetricsMiddleware
      metrics.requestCount.get() shouldBe 2
      metrics.successCount shouldBe 1
      metrics.errorCount shouldBe 1

      // Base mock only called once (rate limiter stopped the second)
      baseMock.callCount.get() shouldBe 1
    }
}
