package org.llm4s.agent.memory

import org.llm4s.types.Result

/**
 * Storage backend for agent memories.
 *
 * MemoryStore provides the interface for persisting and retrieving
 * memories. Implementations can be in-memory (for testing), file-based,
 * database-backed, or use vector databases for semantic search.
 *
 * The trait follows functional principles - operations return new
 * instances rather than mutating state.
 */
trait MemoryStore {

  /**
   * Store a new memory.
   *
   * @param memory The memory to store
   * @return Updated store or error
   */
  def store(memory: Memory): Result[MemoryStore]

  /**
   * Store multiple memories in a batch.
   *
   * @param memories The memories to store
   * @return A new MemoryStore containing the added memories,
   * or an error describing why the operation failed.
   */
  def storeAll(memories: Seq[Memory]): Result[MemoryStore] =
    memories.foldLeft[Result[MemoryStore]](Right(this)) { (storeResult, memory) =>
      storeResult.flatMap(_.store(memory))
    }

  /**
   * Retrieve a memory by its ID.
   *
   * @param id The memory identifier
   * @return The memory if found, or error
   */
  def get(id: MemoryId): Result[Option[Memory]]

  /**
   * Recall memories matching a filter.
   *
   * This is the primary retrieval method for most use cases.
   * Returns memories sorted by relevance (most relevant first).
   *
   * @param filter Criteria for filtering memories
   * @param limit Maximum number of memories to return
   * @return Matching memories sorted by relevance (most relevant first), or error
   */
  def recall(
    filter: MemoryFilter = MemoryFilter.All,
    limit: Int = 100
  ): Result[Seq[Memory]]

  /**
   * Semantic search for memories similar to a query.
   *
   * This method uses embeddings for semantic similarity search.
   * Falls back to keyword search if embeddings are not available.
   *
   * @param query The search query
   * @param topK Number of results to return
   * @param filter Additional filter criteria
   * @return A list of ScoredMemory objects, sorted by relevance score (highest first), or error
   */
  def search(
    query: String,
    topK: Int = 10,
    filter: MemoryFilter = MemoryFilter.All
  ): Result[Seq[ScoredMemory]]

  /**
   * Get all memories for a specific entity.
   *
   * @param entityId The entity identifier
   * @return All memories related to this entity
   */
  def getEntityMemories(entityId: EntityId): Result[Seq[Memory]] =
    recall(MemoryFilter.ByEntity(entityId))

  /**
   * Get conversation history.
   *
   * @param conversationId The conversation identifier
   * @param limit Maximum messages to return
   * @return Conversation memories in chronological order
   */
  def getConversation(conversationId: String, limit: Int = 100): Result[Seq[Memory]] =
    recall(MemoryFilter.ByConversation(conversationId), limit).map(memories => memories.sortBy(_.timestamp))

  /**
   * Delete a memory by its ID.
   *
   * @param id The memory identifier
   * @return Updated store or error
   */
  def delete(id: MemoryId): Result[MemoryStore]

  /**
   * Delete all memories matching a filter.
   *
   * @param filter Criteria for selecting memories to delete
   * @return Updated store or error
   */
  def deleteMatching(filter: MemoryFilter): Result[MemoryStore]

  /**
   * Update a memory.
   *
   * @param id The memory identifier
   * @param update Function to transform the memory
   * @return Updated store or error
   */
  def update(id: MemoryId, update: Memory => Memory): Result[MemoryStore]

  /**
   * Count memories matching a filter.
   *
   * @param filter Criteria for counting memories
   * @return Count or error
   */
  def count(filter: MemoryFilter = MemoryFilter.All): Result[Long]

  /**
   * Check if a memory exists.
   *
   * @param id The memory identifier
   * @return True if memory exists
   */
  def exists(id: MemoryId): Result[Boolean] =
    get(id).map(_.isDefined)

  /**
   * Clear all memories from the store.
   *
   * @return Empty store or error
   */
  def clear(): Result[MemoryStore]

  /**
   * Get the most recent memories.
   *
   * @param limit Maximum number of memories
   * @param filter Additional filter criteria
   * @return Most recent memories
   */
  def recent(limit: Int = 10, filter: MemoryFilter = MemoryFilter.All): Result[Seq[Memory]]

  /**
   * Get high-importance memories.
   *
   * @param threshold Minimum importance score (0.0 to 1.0)
   * @param limit Maximum number of memories
   * @return Important memories
   */
  def important(threshold: Double = 0.5, limit: Int = 100): Result[Seq[Memory]] =
    recall(MemoryFilter.MinImportance(threshold), limit)
}

/**
 * A memory with an associated relevance score.
 *
 * Used in search results to indicate how well a memory
 * matches the search query.
 *
 * @param memory The memory
 * @param score Relevance score (0.0 to 1.0, higher is more relevant)
 */
final case class ScoredMemory(
  memory: Memory,
  score: Double
) {
  require(score >= 0.0 && score <= 1.0, s"Score must be between 0.0 and 1.0, got: $score")
}

object ScoredMemory {

  /**
   * Create a scored memory with perfect relevance.
   */
  def perfect(memory: Memory): ScoredMemory = ScoredMemory(memory, 1.0)

  /**
   * Ordering by score (descending).
   */
  implicit val byScoreDescending: Ordering[ScoredMemory] =
    Ordering.by[ScoredMemory, Double](_.score).reverse
}

/**
 * Configuration for memory store behavior.
 *
 * @param maxMemories Maximum number of memories to retain (None for unlimited)
 * @param defaultEmbeddingDimensions Dimensions for embedding vectors
 * @param enableAutoCleanup Whether to automatically clean old memories
 * @param cleanupThreshold Memory count threshold for triggering cleanup
 */
final case class MemoryStoreConfig(
  maxMemories: Option[Int] = None,
  defaultEmbeddingDimensions: Int = 1536,
  enableAutoCleanup: Boolean = false,
  cleanupThreshold: Int = 10000
)

object MemoryStoreConfig {

  /**
   * Default configuration suitable for most use cases.
   */
  val default: MemoryStoreConfig = MemoryStoreConfig()

  /**
   * Configuration optimized for testing (small limits).
   */
  val testing: MemoryStoreConfig = MemoryStoreConfig(
    maxMemories = Some(1000),
    enableAutoCleanup = false
  )

  /**
   * Configuration for production use with cleanup.
   */
  def production(maxMemories: Int = 100000): MemoryStoreConfig = MemoryStoreConfig(
    maxMemories = Some(maxMemories),
    enableAutoCleanup = true,
    cleanupThreshold = (maxMemories * 0.9).toInt
  )
}
