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

    val hasEmbeddings = inner.all.exists(m => filter.matches(m) && m.isEmbedded)

    if (hasEmbeddings) {
      embeddingService.embed(query) match {
        case Right(queryEmbedding) =>
          inner.search(query, queryEmbedding, topK, filter)
        case Left(_) =>
          inner.search(query, topK, filter)
      }
    } else {
      inner.search(query, topK, filter)
    }
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
