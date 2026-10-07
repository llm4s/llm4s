package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{ Clock, Instant, ZoneId }
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * The snippets of `docs/guide/caching.md`, compiled and run as written against stand-in clients (no network).
 *
 * If a snippet here stops compiling or an assertion fails, the guide is teaching something that no longer
 * works: change the guide and this spec together. Each `snippet` method is the code of one block in the
 * guide, and the assertions pin what the surrounding prose claims. The stand-ins at the bottom replace the
 * real provider clients, which the guide's snippets receive as `baseClient` and `embeddingClient`.
 */
class CachingGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // ---- 3. Caching embeddings

  private def embeddingCacheSnippet(base: EmbeddingClient): CacheStats = {
    import org.llm4s.llmconnect.caching.{ CachedEmbeddingClient, InMemoryEmbeddingCache }
    import org.llm4s.llmconnect.config.EmbeddingModelConfig
    import org.llm4s.llmconnect.model.EmbeddingRequest
    import scala.concurrent.duration._

    val cache  = new InMemoryEmbeddingCache[Seq[Double]](maxSize = 10000, ttl = Some(1.hour))
    val cached = new CachedEmbeddingClient(base, cache)
    val model  = EmbeddingModelConfig("text-embedding-3-small", 1536)

    cached.embed(EmbeddingRequest(Seq("hello", "world"), model)) // two misses, sent as one batched call
    cached.embed(EmbeddingRequest(Seq("hello", "again"), model)) // "hello" is served from the cache
    cached.cacheStats
  }

  private def customKeySnippet(base: EmbeddingClient): Result[EmbeddingResponse] = {
    import org.llm4s.llmconnect.caching.{ CachedEmbeddingClient, InMemoryEmbeddingCache }
    import org.llm4s.llmconnect.caching.CacheKeyGenerator

    // Keys are built from the text and the model name. A custom key function can add what else matters,
    // here a tenant, so two tenants never share a cached vector.
    val tenant = "tenant-a"
    val cache  = new InMemoryEmbeddingCache[Seq[Double]]()
    val cached =
      new CachedEmbeddingClient(base, cache, (text, model) => CacheKeyGenerator.sha256(text, s"$tenant/$model"))

    cached.embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("text-embedding-3-small", 1536)))
  }

  /** A backend of your own: any store that can get and put a vector by key. */
  private class MapEmbeddingCache extends EmbeddingCache[Seq[Double]] {
    private val store  = new java.util.concurrent.ConcurrentHashMap[String, Seq[Double]]()
    private val hits   = new AtomicInteger(0)
    private val misses = new AtomicInteger(0)

    def get(key: String): Option[Seq[Double]] = {
      val found = Option(store.get(key))
      if (found.isDefined) hits.incrementAndGet() else misses.incrementAndGet()
      found
    }
    def put(key: String, embedding: Seq[Double]): Unit = { store.put(key, embedding); () }
    override def clear(): Unit                         = store.clear()
    def stats(): CacheStats = {
      val (h, m) = (hits.get().toLong, misses.get().toLong)
      CacheStats(store.size(), h, m, h + m, if (h + m == 0) 0.0 else 100.0 * h / (h + m))
    }
  }

  // ---- 4. Caching completions

  private def configSnippet(): Result[CacheConfig] = {
    import org.llm4s.llmconnect.caching.CacheConfig
    import scala.concurrent.duration._

    CacheConfig.create(
      similarityThreshold = 0.95, // cosine similarity, 0.0 to 1.0
      ttl = 5.minutes,            // an older entry is ignored
      maxSize = 100               // least recently used entry is evicted beyond this
    )
  }

  private def semanticCacheSnippet(
    baseClient: LLMClient,
    embeddingClient: EmbeddingClient,
    tracing: Tracing,
    cacheConfig: CacheConfig
  ): CachingLLMClient = {
    import org.llm4s.llmconnect.caching.CachingLLMClient
    import org.llm4s.llmconnect.config.EmbeddingModelConfig

    new CachingLLMClient(
      baseClient = baseClient,
      embeddingClient = embeddingClient,
      embeddingModel = EmbeddingModelConfig("text-embedding-3-small", 1536),
      config = cacheConfig,
      tracing = tracing
    )
  }

  private def ttlClockSnippet(
    baseClient: LLMClient,
    embeddingClient: EmbeddingClient,
    tracing: Tracing,
    cacheConfig: CacheConfig,
    clock: Clock
  ): CachingLLMClient =
    // The clock only decides whether an entry is still within its TTL. Tests pass a fixed, movable one.
    new CachingLLMClient(
      baseClient,
      embeddingClient,
      EmbeddingModelConfig("text-embedding-3-small", 1536),
      cacheConfig,
      tracing,
      clock
    )

  // ---- 5. Observing the cache

  private def collectingTracing(): (Tracing, () => List[TraceEvent]) = {
    val events = ListBuffer.empty[TraceEvent]
    val tracing = new Tracing {
      override def traceEvent(event: TraceEvent): Result[Unit] = { events.synchronized(events += event); Right(()) }
      override def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
      override def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
      override def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
      override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
    }
    (tracing, () => events.synchronized(events.toList))
  }

  private def describe(event: TraceEvent): String = event match {
    case TraceEvent.CacheHit(similarity, threshold, _) => f"hit, similarity $similarity%.3f >= $threshold%.3f"
    case TraceEvent.CacheMiss(reason, _)               => s"miss: ${reason.value}"
    case other                                         => other.eventType
  }

  // ----------------------------------------------------------------------------------------------------

  private val ModelA = EmbeddingModelConfig("test-embedding", 3)

  private def conversation(text: String): Conversation = Conversation.userOnly(text).value

  private def cacheConfig(threshold: Double, ttl: FiniteDuration = 1.hour, maxSize: Int = 1000): CacheConfig =
    CacheConfig.create(threshold, ttl, maxSize).value

  "the embedding cache (section 3)" should {
    "serve repeated texts from the cache and batch the misses into one call" in {
      val base  = new FakeEmbeddingClient
      val stats = embeddingCacheSnippet(base)

      base.calls.get shouldBe 2 // one call for [hello, world], one for [again]
      base.requests.map(_.input) shouldBe List(Seq("hello", "world"), Seq("again"))
      stats.size shouldBe 3
      stats.hits shouldBe 1
      stats.misses shouldBe 3
      stats.totalRequests shouldBe 4
      stats.hitRatePercent shouldBe 25.0
    }

    "key on whatever the custom key function says" in {
      val base = new FakeEmbeddingClient
      customKeySnippet(base).value.embeddings should have size 1
      base.calls.get shouldBe 1
    }

    "expire an entry strictly after its TTL, counting the expired read as a miss" in {
      var nowMillis = 0L
      val cache = new InMemoryEmbeddingCache[Seq[Double]](maxSize = 10, ttl = Some(10.seconds), clock = () => nowMillis)
      cache.put("k", Seq(1.0))

      nowMillis = 10000L // exactly the TTL: still valid
      cache.get("k") shouldBe Some(Seq(1.0))
      nowMillis = 10001L // past it: gone, and removed
      cache.get("k") shouldBe None
      cache.stats().size shouldBe 0
      cache.stats().hits shouldBe 1
      cache.stats().misses shouldBe 1
    }

    "evict the least recently used entry, and a read counts as a use" in {
      val cache = new InMemoryEmbeddingCache[Seq[Double]](maxSize = 2, ttl = None)
      cache.put("a", Seq(1.0))
      cache.put("b", Seq(2.0))
      cache.get("a") // a is now the most recently used
      cache.put("c", Seq(3.0))

      cache.get("a") shouldBe defined
      cache.get("b") shouldBe None
      cache.get("c") shouldBe defined
    }

    "forget everything, statistics included, on clearCache" in {
      val base   = new FakeEmbeddingClient
      val cached = new CachedEmbeddingClient(base, new InMemoryEmbeddingCache[Seq[Double]]())
      cached.embed(EmbeddingRequest(Seq("x"), ModelA))
      cached.clearCache()

      cached.cacheStats.size shouldBe 0
      cached.cacheStats.totalRequests shouldBe 0
      cached.embed(EmbeddingRequest(Seq("x"), ModelA))
      base.calls.get shouldBe 2
    }

    "send a text repeated inside one request once, while the statistics count every lookup" in {
      val base   = new FakeEmbeddingClient
      val cached = new CachedEmbeddingClient(base, new InMemoryEmbeddingCache[Seq[Double]]())
      cached.embed(EmbeddingRequest(Seq("a", "a", "b"), ModelA)).value.embeddings should have size 3

      base.requests.map(_.input) shouldBe List(Seq("a", "b"))
      cached.cacheStats.size shouldBe 2
      cached.cacheStats.misses shouldBe 3
    }

    "work with a backend of your own" in {
      val base   = new FakeEmbeddingClient
      val cached = new CachedEmbeddingClient(base, new MapEmbeddingCache)
      cached.embed(EmbeddingRequest(Seq("x"), ModelA))
      cached.embed(EmbeddingRequest(Seq("x"), ModelA))
      base.calls.get shouldBe 1
      cached.cacheStats.hits shouldBe 1
    }

    "not cache a failure" in {
      val base   = new FakeEmbeddingClient
      val cached = new CachedEmbeddingClient(base, new InMemoryEmbeddingCache[Seq[Double]]())
      base.failing = true
      cached.embed(EmbeddingRequest(Seq("x"), ModelA)).isLeft shouldBe true
      base.failing = false
      cached.embed(EmbeddingRequest(Seq("x"), ModelA)).isRight shouldBe true
      base.calls.get shouldBe 2
    }
  }

  "CacheConfig (section 4)" should {
    "build from its three fields" in {
      val config = configSnippet().value
      config.similarityThreshold shouldBe 0.95
      config.ttl shouldBe 5.minutes
      config.maxSize shouldBe 100
    }

    "default maxSize to 1000" in {
      CacheConfig.create(0.9, 1.hour).value.maxSize shouldBe 1000
    }

    "report every violated constraint together" in {
      val message = CacheConfig.create(1.5, 0.seconds, 0).left.value.message
      message should include("similarityThreshold must be between 0.0 and 1.0")
      message should include("ttl must be positive")
      message should include("maxSize must be positive")
    }

    "accept the boundaries 0.0 and 1.0" in {
      CacheConfig.create(0.0, 1.second).isRight shouldBe true
      CacheConfig.create(1.0, 1.second).isRight shouldBe true
    }
  }

  "the semantic cache (section 4)" should {
    def fixture(threshold: Double = 0.9, embedder: FakeEmbeddingClient = new FakeEmbeddingClient) = {
      val llm             = new FakeLLMClient
      val (tracing, seen) = collectingTracing()
      val clock           = new MovableClock
      val client          = ttlClockSnippet(llm, embedder, tracing, cacheConfig(threshold), clock)
      (client, llm, embedder, seen, clock)
    }

    "build with the constructor the sample uses" in {
      val (tracing, _) = collectingTracing()
      val client       = semanticCacheSnippet(new FakeLLMClient, new FakeEmbeddingClient, tracing, cacheConfig(0.9))
      client.complete(conversation("hello")).value.content shouldBe "answer 1"
    }

    "answer an identical request from the cache, without calling the model" in {
      val (client, llm, _, seen, _) = fixture()
      val first                     = client.complete(conversation("What is the capital of France?")).value
      val second                    = client.complete(conversation("What is the capital of France?")).value

      llm.calls.get shouldBe 1
      second shouldBe first
      seen().map(describe) shouldBe List("miss: low_similarity", "hit, similarity 1.000 >= 0.900")
    }

    "count a similarity exactly at the threshold as a hit" in {
      // An identical unit vector has cosine similarity exactly 1.0, and 1.0 is a valid threshold.
      val embedder               = new FakeEmbeddingClient(Map("x" -> Seq(1.0, 0.0, 0.0)))
      val (client, llm, _, _, _) = fixture(threshold = 1.0, embedder = embedder)
      client.complete(conversation("x"))
      client.complete(conversation("x"))
      llm.calls.get shouldBe 1
    }

    "answer a similar request when its embedding is close enough" in {
      val embedder = new FakeEmbeddingClient(
        Map(
          "capital of France" -> Seq(1.0, 0.0, 0.0),
          "France's capital"  -> Seq(0.99, 0.1, 0.0) // cosine similarity about 0.995 with the first
        )
      )
      val (client, llm, _, _, _) = fixture(threshold = 0.95, embedder = embedder)
      client.complete(conversation("What is the capital of France?"))
      client.complete(conversation("Tell me France's capital"))
      llm.calls.get shouldBe 1
    }

    "miss for a different question" in {
      val embedder = new FakeEmbeddingClient(Map("France" -> Seq(1.0, 0.0, 0.0), "Germany" -> Seq(0.0, 1.0, 0.0)))
      val (client, llm, _, seen, _) = fixture(embedder = embedder)
      client.complete(conversation("capital of France"))
      client.complete(conversation("capital of Germany"))
      llm.calls.get shouldBe 2
      seen().map(describe) shouldBe List("miss: low_similarity", "miss: low_similarity")
    }

    "miss when the options differ, and report why" in {
      val (client, llm, _, seen, _) = fixture()
      client.complete(conversation("hello"))
      client.complete(conversation("hello"), CompletionOptions(temperature = 0.9))
      llm.calls.get shouldBe 2
      seen().map(describe) shouldBe List("miss: low_similarity", "miss: options_mismatch")
    }

    "ignore an entry older than the TTL, and report why" in {
      val (client, llm, _, seen, clock) = fixture()
      client.complete(conversation("hello"))
      clock.now = clock.now.plusSeconds(2.hours.toSeconds)
      client.complete(conversation("hello"))
      llm.calls.get shouldBe 2
      seen().map(describe) shouldBe List("miss: low_similarity", "miss: ttl_expired")
    }

    "bypass the cache for streaming, and not fill it" in {
      val (client, llm, _, seen, _) = fixture()
      client.streamComplete(conversation("hello"), CompletionOptions(), _ => ()).isRight shouldBe true
      client.complete(conversation("hello"))
      llm.calls.get shouldBe 2
      seen().map(describe) shouldBe List("miss: low_similarity")
    }

    "not cache a failed model call" in {
      val (client, llm, _, _, _) = fixture()
      llm.failing = true
      client.complete(conversation("hello")).isLeft shouldBe true
      llm.failing = false
      client.complete(conversation("hello")).isRight shouldBe true
      llm.calls.get shouldBe 2
    }

    "skip the cache, silently, when the embedding call fails" in {
      val embedder = new FakeEmbeddingClient
      embedder.failing = true
      val (client, llm, _, seen, _) = fixture(embedder = embedder)
      client.complete(conversation("hello")).isRight shouldBe true
      client.complete(conversation("hello")).isRight shouldBe true
      llm.calls.get shouldBe 2
      seen() shouldBe empty
    }

    "match on user and system messages only: assistant and tool turns are not compared" in {
      // Deliberate: tool arguments and results never reach the embedding request. The consequence is that
      // two conversations differing only in those turns are the same cache key. This embedder would tell
      // the prompts apart if the assistant or tool text were part of them.
      val embedder = new FakeEmbeddingClient(
        Map(
          "thinking" -> Seq(1.0, 0.0, 0.0),
          "other"    -> Seq(0.0, 1.0, 0.0),
          "alpha"    -> Seq(1.0, 0.0, 0.0),
          "beta"     -> Seq(0.0, 1.0, 0.0)
        )
      )
      val (client, llm, _, _, _) = fixture(embedder = embedder)
      val first =
        Conversation(Seq(UserMessage("what is 2+2"), AssistantMessage("thinking"), ToolMessage("alpha", "call-1")))
      val second =
        Conversation(Seq(UserMessage("what is 2+2"), AssistantMessage("other"), ToolMessage("beta", "call-1")))
      client.complete(first)
      client.complete(second)
      llm.calls.get shouldBe 1
    }

    "evict the least recently used entry beyond maxSize" in {
      val embedder = new FakeEmbeddingClient(
        Map("one" -> Seq(1.0, 0.0, 0.0), "two" -> Seq(0.0, 1.0, 0.0), "three" -> Seq(0.0, 0.0, 1.0))
      )
      val llm          = new FakeLLMClient
      val (tracing, _) = collectingTracing()
      val client       = ttlClockSnippet(llm, embedder, tracing, cacheConfig(0.9, maxSize = 2), new MovableClock)

      client.complete(conversation("one"))
      client.complete(conversation("two"))
      client.complete(conversation("one"))   // a hit: "one" is now the most recently used
      client.complete(conversation("three")) // evicts "two"
      llm.calls.get shouldBe 3

      client.complete(conversation("one")) // still cached
      llm.calls.get shouldBe 3
      client.complete(conversation("two")) // was evicted
      llm.calls.get shouldBe 4
    }
  }

  // ---- stand-ins for the real clients ---------------------------------------------------------------

  private class NoProvider extends EmbeddingProvider {
    def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Left(org.llm4s.error.ProcessingError("fake", "the stand-in client never reaches its provider"))
  }

  /** Embeds a text as the vector of the first table entry whose key it contains, else a fixed vector. */
  private class FakeEmbeddingClient(table: Map[String, Seq[Double]] = Map.empty)
      extends EmbeddingClient(new NoProvider(), None, "embedding") {
    val calls                      = new AtomicInteger(0)
    val requests                   = ListBuffer.empty[EmbeddingRequest]
    @volatile var failing: Boolean = false

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      calls.incrementAndGet()
      requests.synchronized(requests += request)
      if (failing) Left(org.llm4s.error.ProcessingError("fake", "embedding service down"))
      else
        Right(
          EmbeddingResponse(
            request.input.map(text =>
              table.collectFirst { case (key, vector) if text.contains(key) => vector }.getOrElse(Seq(0.0, 0.0, 1.0))
            )
          )
        )
    }
  }

  /** Answers "answer N" for its Nth model call. */
  private class FakeLLMClient extends LLMClient {
    val calls                      = new AtomicInteger(0)
    @volatile var failing: Boolean = false

    private def answer(): Result[Completion] = {
      val n = calls.incrementAndGet()
      if (failing) Left(org.llm4s.error.ProcessingError("fake", "model down"))
      else
        Right(
          Completion(
            id = s"id-$n",
            created = 0L,
            content = s"answer $n",
            model = "fake-model",
            message = AssistantMessage(s"answer $n"),
            usage = None
          )
        )
    }

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = answer()
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = answer()
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 100
    override def validate(): Result[Unit]    = Right(())
    override def close(): Unit               = ()
  }

  private class MovableClock extends Clock {
    var now: Instant                           = Instant.parse("2026-01-01T10:00:00Z")
    override def getZone: ZoneId               = ZoneId.of("UTC")
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = now
  }
}
