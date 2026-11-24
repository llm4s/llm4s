package org.llm4s.llmconnect.caching.guide

/**
 * The code blocks of `docs/guide/caching.md`, as written, for [[org.llm4s.llmconnect.caching.CachingGuideSpec]].
 * Each copied block is marked `// guide-block`, and the spec fails when one is not on the page as written
 * ([[org.llm4s.testutil.GuideMirror]]); `DocSnippets` compiles the page itself.
 *
 * This file has no file-level imports and a package of its own, so nothing from
 * `org.llm4s.llmconnect.caching` is in scope by accident: each block compiles with exactly the imports the
 * guide shows for it. The method parameters stand in for the values the guide's prose supplies (`base`,
 * `baseClient`, `embeddingClient`, `tracing`, `cacheConfig`, `clock`); they are not part of the blocks, so
 * their types are fully qualified.
 */
object CachingGuideSnippets {

  // ---- 3. Caching embeddings

  def embeddingCache(
    base: org.llm4s.llmconnect.provider.EmbeddingProvider
  ): org.llm4s.types.Result[Option[org.llm4s.llmconnect.caching.CacheStats]] = {
    // guide-block
    import org.llm4s.llmconnect.caching.{ CachedEmbeddingClient, InMemoryEmbeddingCache }
    import org.llm4s.llmconnect.config.EmbeddingModelConfig
    import org.llm4s.llmconnect.model.EmbeddingRequest
    import scala.concurrent.duration._

    val model = EmbeddingModelConfig("text-embedding-3-small", 1536)

    for {
      cache <- InMemoryEmbeddingCache.create(maxSize = 10000, ttl = Some(1.hour))
      cached = new CachedEmbeddingClient(base, cache)
      _ <- cached.embed(EmbeddingRequest(Seq("hello", "world"), model)) // two misses, sent as one batched call
      _ <- cached.embed(EmbeddingRequest(Seq("hello", "again"), model)) // "hello" is served from the cache
    } yield cached.cacheStats
    // end-guide-block
  }

  def queryCache(
    base: org.llm4s.llmconnect.provider.EmbeddingProvider
  ): org.llm4s.types.Result[Option[org.llm4s.llmconnect.caching.CacheStats]] = {
    // guide-block
    import org.llm4s.llmconnect.caching.{ CachedEmbeddingClient, InMemoryEmbeddingCache }
    import org.llm4s.llmconnect.config.EmbeddingModelConfig
    import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }

    val model = EmbeddingModelConfig("text-embedding-3-small", 1536)

    for {
      cache <- InMemoryEmbeddingCache.create()
      queries = new CachedEmbeddingClient(base, cache)
      _ <- queries.embed(EmbeddingRequest(Seq("what is llm4s?"), model, InputPurpose.Query)) // a miss
      _ <- queries.embed(EmbeddingRequest(Seq("what is llm4s?"), model, InputPurpose.Query)) // a hit
      _ <- queries.embed(EmbeddingRequest(Seq("what is llm4s?"), model))                     // a miss: a document
    } yield queries.cacheStats
    // end-guide-block
  }

  def asEmbeddingClient(base: org.llm4s.llmconnect.provider.EmbeddingProvider)(using
    org.llm4s.model.ModelRegistryService
  ): org.llm4s.types.Result[org.llm4s.llmconnect.EmbeddingClient] = {
    // guide-block
    import org.llm4s.llmconnect.EmbeddingClient
    import org.llm4s.llmconnect.caching.{ CachedEmbeddingClient, InMemoryEmbeddingCache }

    // A CachedEmbeddingClient is an EmbeddingProvider; wrap it where an EmbeddingClient is required.
    InMemoryEmbeddingCache.create().map(cache => EmbeddingClient(new CachedEmbeddingClient(base, cache)))
    // end-guide-block
  }

  class TenantKey(base: org.llm4s.llmconnect.provider.EmbeddingProvider) {
    // guide-block
    import org.llm4s.llmconnect.caching.{ CacheKeyGenerator, CachedEmbeddingClient, InMemoryEmbeddingCache }
    import org.llm4s.llmconnect.config.EmbeddingModelConfig
    import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }

    // The tenant is one more part of the key; sha256 keeps every part apart from the others.
    def tenantKey(tenant: String)(text: String, model: String, purpose: InputPurpose): String =
      CacheKeyGenerator.sha256(tenant, model, purpose.toString, text)

    val cached =
      InMemoryEmbeddingCache.create().map(cache => new CachedEmbeddingClient(base, cache, tenantKey("tenant-a")))

    cached.flatMap(_.embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("text-embedding-3-small", 1536))))
    // end-guide-block
  }

  object OwnStorage {
    // guide-block
    import org.llm4s.llmconnect.caching.{ CacheStats, EmbeddingCache }
    import org.llm4s.types.Result
    import java.util.concurrent.ConcurrentHashMap
    import java.util.concurrent.atomic.AtomicLong

    /** A backend of your own: any store that can get and put a vector by key. */
    class MapEmbeddingCache extends EmbeddingCache {
      private val store  = new ConcurrentHashMap[String, Seq[Double]]()
      private val hits   = new AtomicLong(0)
      private val misses = new AtomicLong(0)

      def get(key: String): Result[Option[Seq[Double]]] = {
        val found = Option(store.get(key))
        if (found.isDefined) hits.incrementAndGet() else misses.incrementAndGet()
        Right(found)
      }
      def put(key: String, embedding: Seq[Double]): Result[Unit] = { store.put(key, embedding); Right(()) }
      def clear(): Result[Unit]                                  = { store.clear(); Right(()) }
      override def stats(): Option[CacheStats] = Some(CacheStats(store.size(), hits.get(), misses.get()))
      // end-guide-block
    }
  }

  // ---- 4. Caching completions

  def config(): org.llm4s.types.Result[org.llm4s.llmconnect.caching.CacheConfig] = {
    // guide-block
    import org.llm4s.llmconnect.caching.CacheConfig
    import scala.concurrent.duration._

    CacheConfig.create(
      similarityThreshold = 0.95, // cosine similarity, 0.0 to 1.0
      ttl = 5.minutes,            // an older entry is ignored
      maxSize = 100               // least recently used entry is evicted beyond this
      // end-guide-block
    )
  }

  def semanticCache(
    baseClient: org.llm4s.llmconnect.LLMClient,
    embeddingClient: org.llm4s.llmconnect.provider.EmbeddingProvider,
    tracing: org.llm4s.trace.Tracing,
    cacheConfig: org.llm4s.llmconnect.caching.CacheConfig
  ): org.llm4s.llmconnect.caching.CachingLLMClient = {
    // guide-block
    import org.llm4s.llmconnect.caching.CachingLLMClient
    import org.llm4s.llmconnect.config.EmbeddingModelConfig

    new CachingLLMClient(
      baseClient = baseClient,
      embeddingClient = embeddingClient,
      embeddingModel = EmbeddingModelConfig("text-embedding-3-small", 1536),
      config = cacheConfig,
      tracing = tracing
    )
    // end-guide-block
  }

  def ttlClock(
    baseClient: org.llm4s.llmconnect.LLMClient,
    embeddingClient: org.llm4s.llmconnect.provider.EmbeddingProvider,
    tracing: org.llm4s.trace.Tracing,
    cacheConfig: org.llm4s.llmconnect.caching.CacheConfig,
    clock: java.time.Clock
  ): org.llm4s.llmconnect.caching.CachingLLMClient = {
    // guide-block
    import org.llm4s.llmconnect.caching.CachingLLMClient
    import org.llm4s.llmconnect.config.EmbeddingModelConfig

    // The clock only decides whether an entry is still within its TTL. Tests pass a fixed, movable one.
    new CachingLLMClient(
      baseClient,
      embeddingClient,
      EmbeddingModelConfig("text-embedding-3-small", 1536),
      cacheConfig,
      tracing,
      clock
    )
    // end-guide-block
  }

  // ---- 5. Seeing what the cache did

  object Observing {
    // guide-block
    import org.llm4s.trace.{ TraceEvent, Tracing }
    import org.llm4s.types.Result
    import scala.collection.mutable.ListBuffer

    def collectingTracing(): (Tracing, () => List[TraceEvent]) = {
      val events = ListBuffer.empty[TraceEvent]
      val tracing = new Tracing {
        override def traceEvent(event: TraceEvent): Result[Unit] = { events.synchronized(events += event); Right(()) }
      }
      (tracing, () => events.synchronized(events.toList))
    }

    def describe(event: TraceEvent): String = event match {
      case TraceEvent.CacheHit(similarity, threshold, _) => f"hit, similarity $similarity%.3f >= $threshold%.3f"
      case TraceEvent.CacheMiss(reason, _)               => s"miss: ${reason.value}"
      case other                                         => other.eventType
      // end-guide-block
    }
  }
}
