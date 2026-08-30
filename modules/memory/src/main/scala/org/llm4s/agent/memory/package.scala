package org.llm4s.agent

/**
 * Memory system for agent context and knowledge retention.
 *
 * The memory package provides a comprehensive system for agents to
 * remember information across conversations, learn about entities,
 * and retrieve relevant context for responses.
 *
 * == Core Concepts ==
 *
 * '''Memory Types:'''
 *  - `Conversation` - Messages from conversation history
 *  - `Entity` - Facts about people, organizations, concepts
 *  - `Knowledge` - Information from external documents
 *  - `UserFact` - Learned user preferences and background
 *  - `Task` - Records of completed tasks and outcomes
 *
 * '''Components:'''
 *  - [[memory.Memory]] - Individual memory entry
 *  - [[memory.MemoryStore]] - Storage backend trait
 *  - [[memory.MemoryManager]] - High-level memory operations
 *  - [[memory.MemoryFilter]] - Composable filtering predicates
 *
 * == Quick Start ==
 *
 * {{{
 * import org.llm4s.agent.memory._
 *
 * // Create a memory manager
 * val manager = SimpleMemoryManager.empty
 *
 * // Record a user fact
 * val updated = manager.recordUserFact(
 *   "User prefers Scala over Java",
 *   userId = Some("user123")
 * )
 *
 * // Record an entity fact
 * val entityId = EntityId.fromName("Scala")
 * val withEntity = updated.flatMap(_.recordEntityFact(
 *   entityId = entityId,
 *   entityName = "Scala",
 *   fact = "Scala is a programming language",
 *   entityType = "technology"
 * ))
 *
 * // Retrieve relevant context for a query
 * val context = withEntity.flatMap(_.getRelevantContext("Tell me about Scala"))
 * }}}
 *
 * == Memory Filtering ==
 *
 * Filters can be composed for complex queries:
 *
 * {{{
 * import org.llm4s.agent.memory.MemoryFilter._
 *
 * // Simple filters
 * val conversationsOnly = conversations
 * val importantOnly = important(0.7)
 *
 * // Combined filters
 * val recentImportant = after(weekAgo) && important(0.5)
 * val entityOrKnowledge = entities || knowledge
 *
 * // Recall with filter
 * store.recall(recentImportant, limit = 10)
 * }}}
 *
 * == Storage Backends ==
 *
 * Multiple backends are available:
 *
 *  - [[memory.InMemoryStore]] - Fast, ephemeral storage for testing
 *  - (Future) SQLiteStore - Persistent local storage
 *  - (Future) VectorStore - Semantic search with embeddings
 *
 * @see [[memory.SimpleMemoryManager]] for basic memory management
 * @see [[memory.MemoryFilter]] for filtering options
 */
package object memory {

  /**
   * Type alias for memory retrieval results with scores.
   */
  type ScoredMemories = Seq[ScoredMemory]

  /**
   * Implicit ordering for memories by timestamp (most recent first).
   */
  implicit val memoryByTimestampDesc: Ordering[Memory] =
    Ordering.by[Memory, java.time.Instant](_.timestamp).reverse

  /**
   * Implicit ordering for memories by importance (highest first).
   */
  val memoryByImportanceDesc: Ordering[Memory] =
    Ordering.by[Memory, Double](_.importance.getOrElse(0.0)).reverse
}
