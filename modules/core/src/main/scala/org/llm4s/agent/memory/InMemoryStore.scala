package org.llm4s.agent.memory

import org.llm4s.error.NotFoundError
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.time.Instant

/**
 * In-memory implementation of MemoryStore.
 *
 * This implementation stores all memories in memory, making it
 * suitable for testing, short-lived agents, and scenarios where
 * persistence isn't required.
 *
 * Features:
 * - Fast lookups using indexed data structures
 * - Basic keyword search (semantic search requires embeddings)
 * - Thread-safe for concurrent access
 * - No external dependencies
 *
 * Limitations:
 * - Data is lost when the application terminates
 * - Memory usage grows with stored memories
 * - Keyword search is less sophisticated than vector search
 *
 * @param memories All stored memories indexed by ID
 * @param config Configuration options
 */
final case class InMemoryStore private (
  private val memories: Map[MemoryId, Memory],
  config: MemoryStoreConfig
) extends MemoryStore {

  override def store(memory: Memory): Result[MemoryStore] = {
    val updated = memories + (memory.id -> memory)

    // Check if we need cleanup
    config.maxMemories match {
      case Some(max) if updated.size > max =>
        // Remove oldest memories to stay under limit
        val toRemove    = updated.size - max
        val sorted      = updated.values.toSeq.sortBy(_.timestamp)
        val idsToRemove = sorted.take(toRemove).map(_.id).toSet
        Right(copy(memories = updated.filterNot { case (id, _) => idsToRemove.contains(id) }))

      case _ =>
        Right(copy(memories = updated))
    }
  }

  override def get(id: MemoryId): Result[Option[Memory]] =
    Right(memories.get(id))

  override def recall(
    filter: MemoryFilter,
    limit: Int
  ): Result[Seq[Memory]] = {
    val filtered = memories.values.filter(filter.matches).toSeq
    val sorted   = filtered.sortBy(_.timestamp)(Ordering[Instant].reverse)
    Right(sorted.take(limit))
  }

  override def search(
    query: String,
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] = {
    if (query.trim.isEmpty) {
      return Right(Seq.empty)
    }
    // First filter by criteria
    val filtered = memories.values.filter(filter.matches).toSeq

    // Check if we have embeddings available
    val hasEmbeddings = filtered.exists(_.isEmbedded)

    if (hasEmbeddings) {
      // TODO: Implement vector similarity search when embeddings are available
      // For now, fall back to keyword search
      keywordSearch(query, filtered, topK)
    } else {
      keywordSearch(query, filtered, topK)
    }
  }

  /**
   * Simple keyword-based search scoring.
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

  override def delete(id: MemoryId): Result[MemoryStore] =
    Right(copy(memories = memories - id))

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] = {
    val idsToDelete = memories.values.filter(filter.matches).map(_.id).toSet
    Right(copy(memories = memories.filterNot { case (id, _) => idsToDelete.contains(id) }))
  }

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    memories.get(id) match {
      case Some(memory) =>
        val updated = updateFn(memory)
        if (updated.id != id) {
          Left(
            ValidationError(
              "id",
              s"update function changed Memory ID from $id to ${updated.id}; IDs must remain constant"
            )
          )
        } else {
          Right(copy(memories = memories + (id -> updated)))
        }

      case None =>
        Left(NotFoundError(s"Memory not found: $id", id.value))
    }

  override def count(filter: MemoryFilter): Result[Long] =
    Right(memories.values.count(filter.matches).toLong)

  override def clear(): Result[MemoryStore] =
    Right(copy(memories = Map.empty))

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] = {
    val filtered = memories.values.filter(filter.matches).toSeq
    val sorted   = filtered.sortBy(_.timestamp)(Ordering[Instant].reverse)
    Right(sorted.take(limit))
  }

  /**
   * Get all memories (for debugging/testing).
   */
  def all: Seq[Memory] = memories.values.toSeq

  /**
   * Get memory count.
   */
  def size: Int = memories.size
}

object InMemoryStore {

  /**
   * Create an empty in-memory store with default configuration.
   */
  def empty: InMemoryStore = InMemoryStore(Map.empty, MemoryStoreConfig.default)

  /**
   * Create an empty in-memory store with custom configuration.
   */
  def apply(config: MemoryStoreConfig = MemoryStoreConfig.default): InMemoryStore =
    InMemoryStore(Map.empty, config)

  /**
   * Create an empty in-memory store with an embedding service.
   *
   * When provided, the store can perform embedding-based semantic search.
   * Returns an EmbeddingMemoryStore that wraps an InMemoryStore.
   */
  def withEmbeddingService(
    service: EmbeddingService,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): EmbeddingMemoryStore =
    EmbeddingMemoryStore(InMemoryStore(config), service)

  /**
   * Create a store pre-populated with memories.
   */
  def withMemories(memories: Seq[Memory]): Result[InMemoryStore] = {
    val store = empty
    memories
      .foldLeft[Result[MemoryStore]](Right(store))((acc, memory) => acc.flatMap(_.store(memory)))
      .map(_.asInstanceOf[InMemoryStore])
  }

  /**
   * Create a store for testing with small limits.
   */
  def forTesting(maxMemories: Int = 1000): InMemoryStore =
    InMemoryStore(MemoryStoreConfig.testing.copy(maxMemories = Some(maxMemories)))
}
