package org.llm4s.agent.memory

import org.llm4s.types.Result

import java.time.Instant

/**
 * Simple implementation of MemoryManager.
 *
 * This implementation provides basic memory management without
 * requiring an LLM for entity extraction or consolidation.
 * It's suitable for most use cases where you don't need
 * automatic entity recognition.
 *
 * For advanced features like LLM-powered entity extraction,
 * use LLMMemoryManager instead.
 *
 * @param store The underlying memory store
 * @param config Configuration options
 */
final case class SimpleMemoryManager private (
  override val store: MemoryStore,
  override val config: MemoryManagerConfig
) extends BaseMemoryManagerOps {

  override def consolidateMemories(
    olderThan: Instant,
    minCount: Int
  ): Result[MemoryManager] =
    // Simple implementation: just return self unchanged
    // Full implementation would summarize old memories using an LLM
    Right(this)

  override def extractEntities(
    text: String,
    conversationId: Option[String]
  ): Result[MemoryManager] =
    // Simple implementation: no entity extraction
    // Full implementation would use NLP or LLM to extract entities
    Right(this)

  override protected def withStore(updatedStore: MemoryStore): MemoryManager =
    copy(store = updatedStore)
}

object SimpleMemoryManager {

  /**
   * Create a memory manager with an empty in-memory store.
   */
  def empty: SimpleMemoryManager =
    SimpleMemoryManager(InMemoryStore.empty, MemoryManagerConfig.default)

  /**
   * Create a memory manager with custom configuration.
   */
  def apply(config: MemoryManagerConfig = MemoryManagerConfig.default): SimpleMemoryManager =
    SimpleMemoryManager(InMemoryStore.empty, config)

  /**
   * Create a memory manager with a specific store.
   */
  def withStore(store: MemoryStore, config: MemoryManagerConfig = MemoryManagerConfig.default): SimpleMemoryManager =
    SimpleMemoryManager(store, config)

  /**
   * Create a memory manager for testing.
   */
  def forTesting: SimpleMemoryManager =
    SimpleMemoryManager(InMemoryStore.forTesting(), MemoryManagerConfig.testing)
}
