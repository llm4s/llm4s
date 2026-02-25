package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import java.time.Instant
import org.llm4s.trace.{ TraceEvent, Tracing }
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

/**
 * Semantic caching wrapper for LLMClient.
 *
 * Caches LLM completions based on the semantic similarity of the prompt request.
 * Useful for reducing costs and latency for repetitive or similar queries.
 *
 * == Usage ==
 * {{{
 * val cachingClient = new CachingLLMClient(
 *   baseClient = openAIClient,
 *   embeddingClient = embeddingClient,
 *   embeddingModel = EmbeddingModelConfig("text-embedding-3-small", 1536),
 *   config = CacheConfig(
 *     similarityThreshold = 0.95,
 *     ttl = 1.hour,
 *     maxSize = 1000
 *   ),
 *   tracing = tracing
 * )
 * }}}
 *
 * == Behavior ==
 * - Computes embedding for the user/system prompt.
 * - Searches cache for entries within `similarityThreshold`.
 * - Validates additional constraints:
 *   - Entry must be within TTL.
 *   - Entry `CompletionOptions` must strictly match the request options.
 * - On Hit: Returns cached `Completion` and updates LRU order. Emits `cache_hit` trace event.
 * - On Miss: Delegating to `baseClient`, caches the result, and emits `cache_miss` trace event.
 *
 * == Limitations ==
 * - `streamComplete` requests bypass the cache entirely.
 * - Cache is in-memory and lost on restart.
 * - Cache lookup involves a linear scan (O(n)) of all entries to calculate cosine similarity.
 *   Performance may degrade with very large `maxSize`.
 *
 * @param baseClient The underlying LLM client to delegate to on cache miss.
 * @param embeddingClient Client to generate embeddings for prompts.
 * @param embeddingModel Configuration for the embedding model used.
 * @param config Cache configuration (threshold, TTL, max size).
 * @param tracing Tracing instance for observability.
 * @param clock Clock for TTL verification (defaults to UTC).
 */
class CachingLLMClient(
  baseClient: LLMClient,
  embeddingClient: EmbeddingClient,
  embeddingModel: EmbeddingModelConfig,
  config: CacheConfig,
  tracing: Tracing,
  clock: java.time.Clock = java.time.Clock.systemUTC()
) extends LLMClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private val cache: java.util.Map[String, CacheEntry] = java.util.Collections.synchronizedMap(
    new java.util.LinkedHashMap[String, CacheEntry](config.maxSize, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, CacheEntry]): Boolean =
        size() > config.maxSize
    }
  )

  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    // SECURITY: Only include User and System messages in the embedding prompt.
    // Exclude Assistant messages (which may contain tool calls) and Tool messages (outputs)
    // to prevent leaking sensitive information found in tool arguments or outputs.
    val promptText = conversation.messages
      .collect {
        case m: UserMessage   => s"${m.role}: ${m.content}"
        case m: SystemMessage => s"${m.role}: ${m.content}"
      }
      .mkString("\n")

    val embeddingReq = EmbeddingRequest(
      input = Seq(promptText),
      model = embeddingModel
    )

    embeddingClient.embed(embeddingReq) match {
      case Right(embeddingResponse) if embeddingResponse.embeddings.nonEmpty =>
        val currentEmbedding = embeddingResponse.embeddings.head
        val now              = Instant.now(clock)
        val ttlNanos         = config.ttl.toNanos

        // Scan cache for semantic candidates and analyze them
        val (hit, missReason) = cache.synchronized {
          val candidates = cache
            .entrySet()
            .asScala
            .map { entry =>
              val similarity =
                org.llm4s.llmconnect.utils.SimilarityUtils.cosineSimilarity(currentEmbedding, entry.getValue.embedding)
              (entry.getKey, entry.getValue, similarity)
            }
            .filter(_._3 >= config.similarityThreshold)
            .toList

          // Find the best valid match (Sim >= Threshold AND Options Match AND TTL Valid)
          val validMatch = candidates
            .filter { case (_, entry, _) =>
              entry.options == options && isWithinTtl(entry.timestamp, ttlNanos, now)
            }
            .maxByOption(_._3)

          validMatch match {
            case Some(matchTuple) => (Some(matchTuple), None)
            case None             =>
              // Analyze why we missed
              val reason = if (candidates.isEmpty) {
                TraceEvent.CacheMissReason.LowSimilarity
              } else {
                // We have matches with high similarity, but they are invalid.
                // Check if any would have been valid except for TTL (i.e., Options matched)
                if (candidates.exists(_._2.options == options)) TraceEvent.CacheMissReason.TtlExpired
                else TraceEvent.CacheMissReason.OptionsMismatch
              }
              (None, Some(reason))
          }
        }

        hit match {
          case Some((key, entry, score)) =>
            logger.debug(s"Cache hit! Similarity: $score > ${config.similarityThreshold}")
            tracing.traceEvent(TraceEvent.CacheHit(score, config.similarityThreshold, now)).left.foreach { err =>
              logger.warn(s"Failed to emit cache hit trace event: ${err.message}")
            }
            cache.get(key) // Update LRU
            Right(entry.response)

          case None =>
            val reasonStr = missReason.map(_.value).getOrElse("unknown")
            logger.debug(s"Cache miss. Reason: $reasonStr. Calling base client.")
            missReason.foreach { reason =>
              tracing.traceEvent(TraceEvent.CacheMiss(reason, now)).left.foreach { err =>
                logger.warn(s"Failed to emit cache miss trace event: ${err.message}")
              }
            }
            executeAndCache(conversation, options, currentEmbedding)
        }

      case Right(_) =>
        logger.warn("Embedding response contained no embeddings. Skipping cache.")
        baseClient.complete(conversation, options)

      case Left(error) =>
        logger.warn(s"Failed to generate embedding: $error. Skipping cache.")
        baseClient.complete(conversation, options)
    }
  }

  /**
   * Returns `true` when `timestamp + ttlNanos` is still in the future.
   *
   * If the addition overflows (e.g. for very large TTL values),
   * `ArithmeticException` is caught and the entry is treated as permanently
   * valid (infinite TTL), which is the safer choice over spuriously expiring
   * entries.
   */
  private def isWithinTtl(timestamp: Instant, ttlNanos: Long, now: Instant): Boolean =
    try
      // Safe check for overflow issues with large TTLs
      timestamp.plusNanos(ttlNanos).isAfter(now)
    catch {
      case _: ArithmeticException => true // Treated as infinite TTL if overflow matches logic
    }

  private def executeAndCache(
    conversation: Conversation,
    options: CompletionOptions,
    embedding: Seq[Double]
  ): Result[Completion] =
    baseClient.complete(conversation, options).map { completion =>
      // Key is not used for lookup (we use embedding similarity scan), but required for the Map.
      // Random UUID is sufficient and ensures uniqueness in the LRU cache.
      cache.put(java.util.UUID.randomUUID().toString, CacheEntry(embedding, completion, Instant.now(clock), options))
      completion
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    // Streaming bypasses cache
    baseClient.streamComplete(conversation, options, onChunk)

  override def getContextWindow(): Int = baseClient.getContextWindow()

  override def getReserveCompletion(): Int = baseClient.getReserveCompletion()

  override def validate(): Result[Unit] = baseClient.validate()

  override def close(): Unit = baseClient.close()
}
