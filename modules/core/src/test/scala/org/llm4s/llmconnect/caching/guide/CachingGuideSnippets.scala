package org.llm4s.llmconnect.caching.guide

/**
 * The code blocks of `docs/guide/caching.md`, as written, for [[org.llm4s.llmconnect.caching.CachingGuideSpec]].
 *
 * This file has no file-level imports and a package of its own, so nothing from
 * `org.llm4s.llmconnect.caching` is in scope by accident: each block compiles with exactly the imports the
 * guide shows for it. The method parameters stand in for the values the guide's prose supplies (`base`,
 * `baseClient`, `embeddingClient`, `tracing`, `cacheConfig`, `clock`); they are not part of the blocks, so
 * their types are fully qualified.
 */
object CachingGuideSnippets {

  // ---- 3. Caching embeddings

  def embeddingCache(base: org.llm4s.llmconnect.EmbeddingClient): org.llm4s.llmconnect.caching.CacheStats = {
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

  class TenantKey(base: org.llm4s.llmconnect.EmbeddingClient) {
    import org.llm4s.llmconnect.caching.{ CacheKeyGenerator, CachedEmbeddingClient, InMemoryEmbeddingCache }
    import org.llm4s.llmconnect.config.EmbeddingModelConfig
    import org.llm4s.llmconnect.model.EmbeddingRequest

    // Every part is prefixed with its length, so no tenant, model or text, colons included, can make two
    // different triples give the same key.
    def tenantKey(tenant: String)(text: String, model: String): String =
      CacheKeyGenerator.sha256(Seq(tenant, model, text).map(part => s"${part.length}:$part").mkString, "")

    val cache  = new InMemoryEmbeddingCache[Seq[Double]]()
    val cached = new CachedEmbeddingClient(base, cache, tenantKey("tenant-a"))

    cached.embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("text-embedding-3-small", 1536)))
  }

  object OwnStorage {
    import org.llm4s.llmconnect.caching.{ CacheStats, EmbeddingCache }
    import java.util.concurrent.ConcurrentHashMap
    import java.util.concurrent.atomic.AtomicInteger

    /** A backend of your own: any store that can get and put a vector by key. */
    class MapEmbeddingCache extends EmbeddingCache[Seq[Double]] {
      private val store  = new ConcurrentHashMap[String, Seq[Double]]()
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
  }

  // ---- 4. Caching completions

  def config(): org.llm4s.types.Result[org.llm4s.llmconnect.caching.CacheConfig] = {
    import org.llm4s.llmconnect.caching.CacheConfig
    import scala.concurrent.duration._

    CacheConfig.create(
      similarityThreshold = 0.95, // cosine similarity, 0.0 to 1.0
      ttl = 5.minutes,            // an older entry is ignored
      maxSize = 100               // least recently used entry is evicted beyond this
    )
  }

  def semanticCache(
    baseClient: org.llm4s.llmconnect.LLMClient,
    embeddingClient: org.llm4s.llmconnect.EmbeddingClient,
    tracing: org.llm4s.trace.Tracing,
    cacheConfig: org.llm4s.llmconnect.caching.CacheConfig
  ): org.llm4s.llmconnect.caching.CachingLLMClient = {
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

  def ttlClock(
    baseClient: org.llm4s.llmconnect.LLMClient,
    embeddingClient: org.llm4s.llmconnect.EmbeddingClient,
    tracing: org.llm4s.trace.Tracing,
    cacheConfig: org.llm4s.llmconnect.caching.CacheConfig,
    clock: java.time.Clock
  ): org.llm4s.llmconnect.caching.CachingLLMClient = {
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
  }

  // ---- 5. Seeing what the cache did

  object Observing {
    import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
    import org.llm4s.trace.{ TraceEvent, Tracing }
    import org.llm4s.types.Result
    import scala.collection.mutable.ListBuffer

    def collectingTracing(): (Tracing, () => List[TraceEvent]) = {
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

    def describe(event: TraceEvent): String = event match {
      case TraceEvent.CacheHit(similarity, threshold, _) => f"hit, similarity $similarity%.3f >= $threshold%.3f"
      case TraceEvent.CacheMiss(reason, _)               => s"miss: ${reason.value}"
      case other                                         => other.eventType
    }
  }
}
