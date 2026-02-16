package org.llm4s.agent.memory

import org.llm4s.types.Result

/**
 * A memory store wrapper that adds embedding-based semantic search to an underlying store.
 *
 * This wrapper implements the decorator pattern - it delegates all standard operations
 * to the inner store while overriding `search()` to use vector similarity when embeddings
 * are available.
 *
 * Binary Compatibility Note:
 * This class was extracted from InMemoryStore to preserve the original case class signature
 * and maintain binary compatibility with v0.1.4.
 *
 * @param inner The underlying memory store
 * @param embeddingService The embedding service for generating query embeddings
 */
final class EmbeddingMemoryStore private (
  inner: InMemoryStore,
  embeddingService: EmbeddingService
) extends MemoryStore {

  override def store(memory: Memory): Result[MemoryStore] =
    inner.store(memory).map {
      case updated: InMemoryStore => new EmbeddingMemoryStore(updated, embeddingService)
      case other                  => other
    }

  override def get(id: MemoryId): Result[Option[Memory]] =
    inner.get(id)

  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    inner.recall(filter, limit)

  override def search(
    query: String,
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] = {
    if (query.trim.isEmpty) {
      return Right(Seq.empty)
    }

    // First filter by criteria
    val filtered = inner.all.filter(filter.matches)

    // Check if we have embeddings available
    val embeddedMemories = filtered.filter(_.isEmbedded)

    if (embeddedMemories.nonEmpty) {
      embeddingService.embed(query) match {
        case Right(queryEmbedding) =>
          val candidates = embeddedMemories.flatMap { memory =>
            memory.embedding.flatMap { vector =>
              if (vector.length != queryEmbedding.length) {
                None
              } else if (containsNonFinite(vector) || containsNonFinite(queryEmbedding)) {
                // Skip memories or queries with non-finite values
                None
              } else {
                val similarity = VectorOps.cosineSimilarity(queryEmbedding, vector)
                // Normalize to 0-1 range
                val normalizedSimilarity = (similarity + 1.0) / 2.0
                val score                = math.max(0.0, math.min(1.0, normalizedSimilarity))
                Some(ScoredMemory(memory, score))
              }
            }
          }

          if (candidates.isEmpty) keywordSearch(query, filtered, topK)
          else Right(candidates.sorted(ScoredMemory.byScoreDescending).take(topK))

        case Left(_) =>
          // Fallback to keyword search when embedding fails
          keywordSearch(query, filtered, topK)
      }
    } else {
      keywordSearch(query, filtered, topK)
    }
  }

  /**
   * Simple keyword-based search scoring (substring matching).
   */
  private def keywordSearch(
    query: String,
    memories: Seq[Memory],
    topK: Int
  ): Result[Seq[ScoredMemory]] = {
    val queryTerms = query.toLowerCase.split("\\s+").toSet

    val scored = memories.map { memory =>
      val content      = memory.content.toLowerCase
      val matchedTerms = queryTerms.count(content.contains)
      val score        = if (queryTerms.isEmpty) 0.0 else matchedTerms.toDouble / queryTerms.size
      ScoredMemory(memory, score)
    }

    val sorted = scored
      .filter(_.score > 0)
      .sorted(ScoredMemory.byScoreDescending)
      .take(topK)

    Right(sorted)
  }

  /**
   * Check if an array contains any non-finite values (NaN, Inf, -Inf).
   */
  private def containsNonFinite(arr: Array[Float]): Boolean = {
    var i = 0
    while (i < arr.length) {
      if (!java.lang.Float.isFinite(arr(i))) return true
      i += 1
    }
    false
  }

  override def delete(id: MemoryId): Result[MemoryStore] =
    inner.delete(id).map {
      case updated: InMemoryStore => new EmbeddingMemoryStore(updated, embeddingService)
      case other                  => other
    }

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    inner.deleteMatching(filter).map {
      case updated: InMemoryStore => new EmbeddingMemoryStore(updated, embeddingService)
      case other                  => other
    }

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    inner.update(id, updateFn).map {
      case updated: InMemoryStore => new EmbeddingMemoryStore(updated, embeddingService)
      case other                  => other
    }

  override def count(filter: MemoryFilter): Result[Long] =
    inner.count(filter)

  override def clear(): Result[MemoryStore] =
    inner.clear().map {
      case updated: InMemoryStore => new EmbeddingMemoryStore(updated, embeddingService)
      case other                  => other
    }

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] =
    inner.recent(limit, filter)

  /**
   * Get all memories (for debugging/testing).
   */
  def all: Seq[Memory] = inner.all

  /**
   * Get memory count.
   */
  def size: Int = inner.size

  /**
   * Get the underlying InMemoryStore.
   */
  def underlying: InMemoryStore = inner
}

object EmbeddingMemoryStore {

  /**
   * Create an embedding memory store wrapping an InMemoryStore.
   */
  def apply(inner: InMemoryStore, service: EmbeddingService): EmbeddingMemoryStore =
    new EmbeddingMemoryStore(inner, service)

  /**
   * Create an empty embedding memory store with default configuration.
   */
  def empty(service: EmbeddingService): EmbeddingMemoryStore =
    new EmbeddingMemoryStore(InMemoryStore.empty, service)

  /**
   * Create an empty embedding memory store with custom configuration.
   */
  def apply(service: EmbeddingService, config: MemoryStoreConfig): EmbeddingMemoryStore =
    new EmbeddingMemoryStore(InMemoryStore(config), service)
}
