package org.llm4s.agent.memory

import org.llm4s.types.Result

/**
 * A memory store wrapper that adds embedding-based semantic search to an underlying store.
 *
 * This wrapper implements the decorator pattern - it delegates all standard operations
 * to the inner store while overriding `search()` to use vector similarity when embeddings
 * are available. It embeds every memory it stores without an embedding, and a memory whose content an `update`
 * changes, so what is recorded through a [[MemoryManager]] is found by similarity.
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

  /** Store `memory`, embedding it first if it has no embedding: a memory without one is never found by similarity. */
  override def store(memory: Memory): Result[MemoryStore] =
    embedded(Seq(memory)).flatMap(ms => inner.store(ms.head)).map(rewrap)

  /** Store `memories`, embedding those without an embedding first, in batches; none is stored if embedding fails. */
  override def storeAll(memories: Seq[Memory]): Result[MemoryStore] =
    embedded(memories).flatMap(inner.storeAll).map(rewrap)

  private def embedded(memories: Seq[Memory]): Result[Seq[Memory]] =
    MemoryEmbeddings.embedMissing(embeddingService, memories, "embedding-memory-store")

  private def rewrap(store: MemoryStore): MemoryStore = store match {
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
      embeddingService.embedQuery(query) match {
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

  /** Update a memory; one whose content changes is embedded again, unless `updateFn` gave it a new embedding. */
  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    inner.get(id).flatMap {
      case scala.None => inner.update(id, updateFn).map(rewrap) // the inner store reports the missing memory
      case Some(existing) =>
        embedded(Seq(MemoryEmbeddings.afterUpdate(existing, updateFn(existing))))
          .flatMap(ms => inner.update(id, _ => ms.head))
          .map(rewrap)
    }

  override def count(filter: MemoryFilter): Result[Long] =
    inner.count(filter)

  override def stats: Result[MemoryStats] = inner.stats

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
