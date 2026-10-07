---
layout: page
title: Caching
parent: User Guide
nav_order: 7
---

# Caching Responses and Embeddings
{: .no_toc }

Avoid paying twice for the same work: an embedding cache for exact repeats, and a semantic cache for model responses.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## 1. What is available

Both caches live in `llm4s-core`, package `org.llm4s.llmconnect.caching`. Both keep their entries in memory, inside the object you create, and lose them when the process ends.

| | Embedding cache | Completion (semantic) cache |
|---|---|---|
| Class | `CachedEmbeddingClient` with an `EmbeddingCache`, by default `InMemoryEmbeddingCache` | `CachingLLMClient`, which wraps any `LLMClient` |
| A hit needs | the same text and the same model name | an embedding at least `similarityThreshold` similar, the same `CompletionOptions`, and an entry younger than the TTL |
| Configured with | `InMemoryEmbeddingCache(maxSize = 10000, ttl = None)` | `CacheConfig.create(similarityThreshold, ttl, maxSize = 1000)` |
| Eviction | least recently used, plus the optional TTL | least recently used, plus the TTL |
| Seeing what happened | `cacheStats` | `TraceEvent.CacheHit` and `TraceEvent.CacheMiss` sent to your `Tracing` |

There is no configuration-file key for either cache. You construct them in code, and the examples below do exactly that.

## 2. Choosing between them

**The embedding cache is exact, so it is safe.** The same text embedded with the same model gives the same vector, so a cached vector is as good as a fresh one. Use it whenever you embed the same texts more than once: re-indexing documents, repeated queries in a RAG service, evaluation runs.

**The completion cache is approximate, so it can be wrong.** It answers a new question with the stored answer to an earlier, *similar* one. That saves a model call, but:

- It costs one embedding call on every `complete()`, hit or miss, because the prompt has to be embedded to be compared. The cache pays off only if a model call is worth more than that embedding call and your hit rate is high enough.
- A hit can be a false hit: two prompts the embedding model considers close may need different answers.
- A cached answer replaces a fresh sample. If you want a different answer each time, or an answer that depends on the current time, the user or fresh data, do not cache it.

## 3. Caching embeddings

Wrap an `EmbeddingClient` in a `CachedEmbeddingClient` and call `embed` on the wrapper:

```scala
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
```

Here `base` is the `EmbeddingClient` you would use without a cache. The wrapper:

- looks every input text up first, and sends only the texts it does not have to the base client, in **one** batched request, then returns the vectors in the original input order;
- sends a text that is repeated inside one request once;
- never caches a failure: an error from the base client is returned as it is, and the next call tries again;
- is **not** an `EmbeddingClient`. It has the same `embed` method, but it cannot be passed where an `EmbeddingClient` is required, so call it directly.

`cacheStats` returns `CacheStats(size, hits, misses, totalRequests, hitRatePercent)`. Every looked-up text counts, so the two calls above give 3 misses and 1 hit (25%), and a text repeated inside one request counts once per occurrence. `clearCache()` empties the cache and resets the statistics.

### The in-memory cache

`InMemoryEmbeddingCache(maxSize = 10000, ttl = None)`:

- **Size.** At more than `maxSize` entries the least recently used one is evicted. Reading an entry counts as using it.
- **TTL.** With `ttl = Some(d)` an entry is expired once it is *older* than `d`. An entry exactly `d` old is still valid. Expiry is lazy: an expired entry is removed when it is read, and that read counts as a miss.
- **Threads.** It is safe to share between threads.

### What the key contains

The default key is the SHA-256 of the text and the model name joined with a colon, as 64 hex characters. Two things follow from that:

- The key holds the model *name* only. It does not include the embedding dimension or the provider, so one cache shared by clients that use the same model name for different things would mix their vectors.
- The text and the model name are joined with `:` before hashing, so the pair is not uniquely encoded: the text `a:b` with model `c` and the text `a` with model `b:c` give the same key.

If either matters to you, pass your own key function as the third argument. This one adds a tenant to every key:

```scala
import org.llm4s.llmconnect.caching.{ CachedEmbeddingClient, InMemoryEmbeddingCache }
import org.llm4s.llmconnect.caching.CacheKeyGenerator

// Keys are built from the text and the model name. A custom key function can add what else matters,
// here a tenant, so two tenants never share a cached vector.
val tenant = "tenant-a"
val cache  = new InMemoryEmbeddingCache[Seq[Double]]()
val cached =
  new CachedEmbeddingClient(base, cache, (text, model) => CacheKeyGenerator.sha256(text, s"$tenant/$model"))

cached.embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("text-embedding-3-small", 1536)))
```

### Your own storage

`EmbeddingCache[A]` is a small trait: `get`, `put`, `stats`, and an optional `clear`. Implement it to keep vectors somewhere else (a shared map, Redis, a database). Expiry and eviction are then your backend's job:

```scala
/** A backend of your own: any store that can get and put a vector by key. */
class MapEmbeddingCache extends EmbeddingCache[Seq[Double]] {
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
```

### Limits

- **No request coalescing.** The cache is checked and then filled in separate steps. If several threads miss the same text at the same moment, each one calls the base client; the last write wins. Four concurrent requests for the same new text made four provider calls.

## 4. Caching model responses

### `CacheConfig`

Build a `CacheConfig` with `CacheConfig.create`. It returns a `Result`, because the values are checked:

```scala
import org.llm4s.llmconnect.caching.CacheConfig
import scala.concurrent.duration._

CacheConfig.create(
  similarityThreshold = 0.95, // cosine similarity, 0.0 to 1.0
  ttl = 5.minutes,            // an older entry is ignored
  maxSize = 100               // least recently used entry is evicted beyond this
)
```

| Field | Type | Meaning |
|---|---|---|
| `similarityThreshold` | `Double`, 0.0 to 1.0 inclusive | the minimum cosine similarity between the new prompt's embedding and a stored one for a hit |
| `ttl` | `FiniteDuration`, positive | an entry older than this is ignored |
| `maxSize` | `Int`, positive, default `1000` | the number of entries kept; beyond it the least recently used is evicted |

`create` reports every violated constraint at once, in the message of the `ValidationError` it returns. There is no public constructor: `CacheConfig(...)` without `create` does not compile.

### The caching client

`CachingLLMClient` takes the client to wrap, an `EmbeddingClient` and the embedding model it should use, the `CacheConfig`, and a `Tracing`. This is the shape the [`SemanticCachingSample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cache/SemanticCachingSample.scala) uses:

```scala
import org.llm4s.llmconnect.caching.CachingLLMClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig

new CachingLLMClient(
  baseClient = baseClient,
  embeddingClient = embeddingClient,
  embeddingModel = EmbeddingModelConfig("text-embedding-3-small", 1536),
  config = cacheConfig,
  tracing = tracing
)
```

It is an `LLMClient`, so it goes anywhere the client you wrap would go. The sample shows it end to end against a real provider: it needs an OpenAI key (see [running the samples](../getting-started/configuration#running-the-samples)), then

```
sbt "samples/runMain org.llm4s.samples.cache.SemanticCachingSample"
```

### How a lookup works

On each `complete(conversation, options)`:

1. The cache builds a prompt from the **user and system messages** only, as lines of `role: content`, and embeds it with your `EmbeddingClient`. That is one embedding call, and the prompt text goes to your embedding provider.
2. It compares that embedding with every stored one by cosine similarity. A stored entry is a candidate when its similarity is **at or above** `similarityThreshold`.
3. A candidate is a hit when its `CompletionOptions` are **equal** to the request's (temperature, tools and the rest) and it is **within the TTL**. If several qualify, the most similar wins.
4. On a hit the stored `Completion` is returned and the entry counts as recently used. It is the same object each time, so do not mutate it.
5. On a miss the base client is called, a successful result is stored, and it is returned.

### What is, and is not, compared

Only user and system messages are embedded. Assistant messages and tool messages are left out on purpose, so that tool arguments and tool results never reach the embedding provider. The consequence is that **two conversations that differ only in their assistant or tool turns look identical to the cache**:

```text
[user: "what is 2+2", assistant: "thinking", tool: "4"]
[user: "what is 2+2", assistant: "other",    tool: "5"]   -> served the first one's answer
```

For a multi-turn agent run this can return an answer that does not match the tool results in front of it. Cache the single-shot, self-contained calls, not the steps of an agent loop.

### Choosing the threshold

A high threshold gives few false hits and few hits; a low one the opposite. The right value depends on the embedding model and on your questions, so measure it on real queries. The sample uses `0.95`.

Do not use `1.0` to mean "exact match". Similarity is computed in floating point, and even an identical prompt can come out a hair below `1.0`: with an embedding of `(0.3, 0.3, 0.3)` the same prompt asked twice was a miss at a threshold of `1.0`. If you want exact matching, use the exact embedding cache above, or a threshold just under `1.0`.

### TTL, size and memory

- An entry older than `ttl` is skipped, but it is **not removed**. It stays in memory until the least-recently-used rule evicts it, so `maxSize`, not `ttl`, is what bounds memory.
- The lookup compares the new embedding with every stored entry, so its cost grows with `maxSize`. Keep it modest.
- Use the `clock` constructor parameter to control time in tests. It is only consulted for the TTL:

```scala
// The clock only decides whether an entry is still within its TTL. Tests pass a fixed, movable one.
new CachingLLMClient(
  baseClient,
  embeddingClient,
  EmbeddingModelConfig("text-embedding-3-small", 1536),
  cacheConfig,
  tracing,
  clock
)
```

### What is never cached

- **Streaming.** `streamComplete` goes straight to the base client and neither reads nor fills the cache.
- **Failures.** If the base client returns an error, the error is returned and nothing is stored.
- **When the embedding call fails.** If embedding the prompt fails or returns nothing, the request goes to the base client uncached. The only trace of it is a warning in the log: no trace event is emitted, so a cache whose embedding service is down looks like a cache that always misses.

### Sharing, privacy and threads

- The entries belong to that `CachingLLMClient` instance. Everyone who uses the instance shares them; nothing is keyed by user or tenant. Do not share one instance between users whose answers must not mix, and give each its own.
- The entries live in memory, and a restart empties the cache.
- It is safe to call from several threads. The model calls themselves are not serialised, and like the embedding cache it does not coalesce: several threads asking the same new question at once all call the model (four concurrent identical requests made four model calls).
- The completion cache has no way to inspect or clear it. To start again, create a new instance.

## 5. Seeing what the cache did

The completion cache reports each decision to the `Tracing` you give it. A hit sends `TraceEvent.CacheHit(similarity, threshold, timestamp)`; a miss sends `TraceEvent.CacheMiss(reason, timestamp)`, where the reason says why:

| `reason` | `reason.value` | Meaning |
|---|---|---|
| `LowSimilarity` | `low_similarity` | no stored entry reached the threshold (this includes an empty cache) |
| `TtlExpired` | `ttl_expired` | entries reached the threshold, and some had the same options, but none was within the TTL |
| `OptionsMismatch` | `options_mismatch` | entries reached the threshold, but none had the same `CompletionOptions` |

A `Tracing` that records them, and a way to print one:

```scala
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
```

Asking the same question twice then gives `miss: low_similarity` followed by `hit, similarity 1.000 >= 0.900` (for a threshold of `0.900`). The bundled tracing backends already handle both events: `ConsoleTracing` prints `CACHE HIT` (with the similarity and threshold) and `CACHE MISS` (with the reason), the Langfuse backend records a `Cache Hit` or `Cache Miss` span with the same values, and the OpenTelemetry backend records a `Cache Hit` or `Cache Miss` event with `similarity`, `threshold` and `reason` attributes.

The embedding cache has no trace events; read its `cacheStats` instead (section 3).

## 6. Where caching does not belong

- Calls that should return a fresh, varied or personalised answer.
- Calls with side effects, such as the steps of an agent that runs tools.
- Anything where a wrong answer from a near-miss prompt costs more than the model call saved.
- Several users on one `CachingLLMClient`, when their answers must stay separate.

## See also

- [Error Handling](error-handling): the `Result` and `LLMError` types every call here returns.
- [Vector Store](vector-store): where the vectors of an indexed corpus live, which a cache does not replace.
- [`SemanticCachingSample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cache/SemanticCachingSample.scala): the cache against a real provider.
