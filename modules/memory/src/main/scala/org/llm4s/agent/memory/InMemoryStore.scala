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
 * - Whole-word keyword search, case-insensitive (semantic search requires embeddings)
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
    val filtered = memories.values.filter(filter.matches).toSeq
    keywordSearch(query, filtered, topK)
  }

  /**
   * Semantic search for memories similar to an embedded query vector.
   *
   * Computes cosine similarity between the query embedding and the stored
   * memory embeddings, returning the top-K results.
   */
  def search(
    query: String,
    queryEmbedding: Array[Float],
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] = {
    if (queryEmbedding.isEmpty) {
      return Right(Seq.empty)
    }

    val filtered         = memories.values.filter(filter.matches).toSeq
    val embeddedMemories = filtered.filter(_.isEmbedded)

    if (embeddedMemories.nonEmpty) {
      val queryNonFinite = containsNonFinite(queryEmbedding)
      val candidates = embeddedMemories.flatMap { memory =>
        memory.embedding.flatMap { vector =>
          if (vector.length != queryEmbedding.length) {
            None
          } else if (queryNonFinite || containsNonFinite(vector)) {
            None
          } else {
            val similarity           = VectorOps.cosineSimilarity(queryEmbedding, vector)
            val normalizedSimilarity = (similarity + 1.0) / 2.0
            val score                = math.max(0.0, math.min(1.0, normalizedSimilarity))
            Some(ScoredMemory(memory, score))
          }
        }
      }

      if (candidates.isEmpty) keywordSearch(query, filtered, topK)
      else Right(candidates.sorted(ScoredMemory.byScoreDescending).take(topK))
    } else {
      keywordSearch(query, filtered, topK)
    }
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

  /**
   * Keyword search: a memory's score is the share of the query's distinct phrases that occur in it as whole words.
   *
   * The query is split into phrases and query and memory text into words by [[KeywordTokens]], the way
   * `SQLiteMemoryStore` and its FTS5 index split them, so both stores find the same memories: `java?` matches `Java`,
   * and `or` does not match `works`. They rank them differently (FTS5 ranks by bm25).
   */
  private def keywordSearch(
    query: String,
    memories: Seq[Memory],
    topK: Int
  ): Result[Seq[ScoredMemory]] = {
    val phrases = KeywordTokens.queryPhrases(query).map(KeywordTokens.words).filter(_.nonEmpty).distinct

    if (phrases.isEmpty) Right(Seq.empty)
    else {
      val scored = memories.map { memory =>
        val words   = KeywordTokens.words(memory.content)
        val matched = phrases.count(phrase => words.containsSlice(phrase))
        ScoredMemory(memory, matched.toDouble / phrases.size)
      }

      Right(
        scored
          .filter(_.score > 0)
          .sorted(ScoredMemory.byScoreDescending)
          .take(topK)
      )
    }
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
