package org.llm4s.agent.memory

import org.llm4s.llmconnect.model.Message
import org.llm4s.types.Result

/**
 * High-level memory management for agents.
 *
 * MemoryManager provides semantic operations on top of the raw
 * MemoryStore, including entity extraction, memory consolidation,
 * and intelligent retrieval strategies.
 *
 * This is the primary interface for agent memory integration.
 */
trait MemoryManager {

  /**
   * The underlying memory store.
   */
  def store: MemoryStore

  /**
   * Record a conversation turn.
   *
   * @param message The message to record
   * @param conversationId Identifier for this conversation
   * @param importance Optional importance score
   * @return Updated manager or error
   */
  def recordMessage(
    message: Message,
    conversationId: String,
    importance: Option[Double] = None
  ): Result[MemoryManager]

  /**
   * Record a complete conversation.
   *
   * @param messages The conversation messages
   * @param conversationId Identifier for this conversation
   * @return Updated manager or error
   */
  def recordConversation(
    messages: Seq[Message],
    conversationId: String
  ): Result[MemoryManager]

  /**
   * Store a fact about an entity.
   *
   * @param entityId The entity this fact is about
   * @param entityName Human-readable entity name
   * @param fact The fact to store
   * @param entityType Type of entity (person, organization, concept, etc.)
   * @param importance Optional importance score
   * @return Updated manager or error
   */
  def recordEntityFact(
    entityId: EntityId,
    entityName: String,
    fact: String,
    entityType: String = "unknown",
    importance: Option[Double] = None
  ): Result[MemoryManager]

  /**
   * Store a fact about the user.
   *
   * @param fact The fact about the user
   * @param userId Optional user identifier
   * @param importance Optional importance score
   * @return Updated manager or error
   */
  def recordUserFact(
    fact: String,
    userId: Option[String] = None,
    importance: Option[Double] = None
  ): Result[MemoryManager]

  /**
   * Store knowledge from an external source.
   *
   * @param content The knowledge content
   * @param source Source identifier (file path, URL, etc.)
   * @param metadata Additional metadata
   * @return Updated manager or error
   */
  def recordKnowledge(
    content: String,
    source: String,
    metadata: Map[String, String] = Map.empty
  ): Result[MemoryManager]

  /**
   * Record a completed task and its outcome.
   *
   * @param description Task description
   * @param outcome What happened
   * @param success Whether the task succeeded
   * @param importance Optional importance score
   * @return Updated manager or error
   */
  def recordTask(
    description: String,
    outcome: String,
    success: Boolean,
    importance: Option[Double] = None
  ): Result[MemoryManager]

  /**
   * Retrieve relevant context for a query.
   *
   * This is the main method for memory-augmented generation.
   * Returns memories most relevant to the given query, formatted
   * as context for the LLM.
   *
   * @param query The user's query
   * @param maxTokens Approximate maximum tokens of context
   * @param filter Additional filter criteria
   * @return Formatted context string
   */
  def getRelevantContext(
    query: String,
    maxTokens: Int = 2000,
    filter: MemoryFilter = MemoryFilter.All
  ): Result[String]

  /**
   * Get recent conversation context.
   *
   * @param conversationId The conversation to retrieve
   * @param maxMessages Maximum messages to include
   * @return Formatted conversation history
   */
  def getConversationContext(
    conversationId: String,
    maxMessages: Int = 20
  ): Result[String]

  /**
   * Get all known facts about an entity.
   *
   * @param entityId The entity to query
   * @return Formatted entity knowledge
   */
  def getEntityContext(entityId: EntityId): Result[String]

  /**
   * Get facts known about the user.
   *
   * @param userId Optional user identifier
   * @return Formatted user knowledge
   */
  def getUserContext(userId: Option[String] = None): Result[String]

  /**
   * Summarize and consolidate old memories.
   *
   * This operation can reduce memory usage by combining
   * related memories into summaries.
   *
   * @param olderThan Only consolidate memories older than this
   * @param minCount Minimum memories to trigger consolidation
   * @return Updated manager or error
   */
  def consolidateMemories(
    olderThan: java.time.Instant,
    minCount: Int = 10
  ): Result[MemoryManager]

  /**
   * Extract and store entity mentions from text.
   *
   * Uses NLP or LLM to identify entities in the text
   * and create entity memories.
   *
   * @param text Text to analyze
   * @param conversationId Optional conversation context
   * @return Updated manager with extracted entities
   */
  def extractEntities(
    text: String,
    conversationId: Option[String] = None
  ): Result[MemoryManager]

  /**
   * Get memory statistics.
   */
  def stats: Result[MemoryStats]
}

/**
 * Statistics about memory usage.
 *
 * @param totalMemories Total number of memories stored
 * @param byType Count of memories by type
 * @param entityCount Number of distinct entities
 * @param conversationCount Number of distinct conversations
 * @param embeddedCount Number of memories with embeddings
 * @param oldestMemory Timestamp of oldest memory
 * @param newestMemory Timestamp of newest memory
 */
final case class MemoryStats(
  totalMemories: Long,
  byType: Map[MemoryType, Long],
  entityCount: Long,
  conversationCount: Long,
  embeddedCount: Long,
  oldestMemory: Option[java.time.Instant],
  newestMemory: Option[java.time.Instant]
)

object MemoryStats {

  /**
   * Empty statistics.
   */
  val empty: MemoryStats = MemoryStats(
    totalMemories = 0,
    byType = Map.empty,
    entityCount = 0,
    conversationCount = 0,
    embeddedCount = 0,
    oldestMemory = None,
    newestMemory = None
  )
}

/**
 * Configuration for memory manager behavior.
 *
 * Refactored from case class to regular class to maintain binary compatibility
 * when adding new parameters.
 *
 * @param autoRecordMessages Whether to automatically record conversation messages
 * @param autoExtractEntities Whether to automatically extract entities from messages
 * @param defaultImportance Default importance score for unscored memories
 * @param contextTokenBudget Default token budget for context retrieval
 * @param consolidationEnabled Whether to enable automatic memory consolidation
 * @param consolidationConfig Configuration for memory consolidation behavior
 */
final class MemoryManagerConfig(
  val autoRecordMessages: Boolean = true,
  val autoExtractEntities: Boolean = false,
  val defaultImportance: Double = 0.5,
  val contextTokenBudget: Int = 2000,
  val consolidationEnabled: Boolean = false,
  val consolidationConfig: ConsolidationConfig = ConsolidationConfig.default
) {

  /**
   * Binary-compatible 5-parameter constructor.
   * Preserves the old constructor signature for code compiled against pre-0.1.4 versions.
   */
  def this(
    autoRecordMessages: Boolean,
    autoExtractEntities: Boolean,
    defaultImportance: Double,
    contextTokenBudget: Int,
    consolidationEnabled: Boolean
  ) = this(
    autoRecordMessages,
    autoExtractEntities,
    defaultImportance,
    contextTokenBudget,
    consolidationEnabled,
    ConsolidationConfig.default
  )

  /**
   * Copy method with all 6 parameters.
   */
  def copy(
    autoRecordMessages: Boolean = this.autoRecordMessages,
    autoExtractEntities: Boolean = this.autoExtractEntities,
    defaultImportance: Double = this.defaultImportance,
    contextTokenBudget: Int = this.contextTokenBudget,
    consolidationEnabled: Boolean = this.consolidationEnabled,
    consolidationConfig: ConsolidationConfig = this.consolidationConfig
  ): MemoryManagerConfig = new MemoryManagerConfig(
    autoRecordMessages,
    autoExtractEntities,
    defaultImportance,
    contextTokenBudget,
    consolidationEnabled,
    consolidationConfig
  )

  /**
   * Binary-compatible 5-parameter copy method.
   * Preserves the old copy signature for code compiled against pre-0.1.4 versions.
   */
  def copy(
    autoRecordMessages: Boolean,
    autoExtractEntities: Boolean,
    defaultImportance: Double,
    contextTokenBudget: Int,
    consolidationEnabled: Boolean
  ): MemoryManagerConfig = new MemoryManagerConfig(
    autoRecordMessages,
    autoExtractEntities,
    defaultImportance,
    contextTokenBudget,
    consolidationEnabled,
    ConsolidationConfig.default
  )

  override def equals(obj: Any): Boolean = obj match {
    case that: MemoryManagerConfig =>
      this.autoRecordMessages == that.autoRecordMessages &&
      this.autoExtractEntities == that.autoExtractEntities &&
      this.defaultImportance == that.defaultImportance &&
      this.contextTokenBudget == that.contextTokenBudget &&
      this.consolidationEnabled == that.consolidationEnabled &&
      this.consolidationConfig == that.consolidationConfig
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(
      autoRecordMessages,
      autoExtractEntities,
      defaultImportance,
      contextTokenBudget,
      consolidationEnabled,
      consolidationConfig
    )
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString: String =
    s"MemoryManagerConfig(" +
      s"autoRecordMessages=$autoRecordMessages, " +
      s"autoExtractEntities=$autoExtractEntities, " +
      s"defaultImportance=$defaultImportance, " +
      s"contextTokenBudget=$contextTokenBudget, " +
      s"consolidationEnabled=$consolidationEnabled, " +
      s"consolidationConfig=$consolidationConfig)"
}

/**
 * Configuration for memory consolidation behavior.
 *
 * This nested config contains consolidation-specific settings and is only used
 * when consolidationEnabled is true.
 *
 * @param maxMemoriesPerGroup Maximum memories per consolidation group (prevents unbounded context)
 * @param strictMode If true, fail fast on any consolidation error. If false, log and continue (best-effort)
 */
final case class ConsolidationConfig(
  maxMemoriesPerGroup: Int = 50,
  strictMode: Boolean = false
)

object ConsolidationConfig {

  /**
   * Default consolidation configuration (best-effort mode).
   */
  val default: ConsolidationConfig = ConsolidationConfig()

  /**
   * Strict consolidation configuration (fails fast on any error).
   */
  val strict: ConsolidationConfig = ConsolidationConfig(strictMode = true)
}

object MemoryManagerConfig {

  /**
   * Factory method with all 6 parameters.
   */
  def apply(
    autoRecordMessages: Boolean = true,
    autoExtractEntities: Boolean = false,
    defaultImportance: Double = 0.5,
    contextTokenBudget: Int = 2000,
    consolidationEnabled: Boolean = false,
    consolidationConfig: ConsolidationConfig = ConsolidationConfig.default
  ): MemoryManagerConfig = new MemoryManagerConfig(
    autoRecordMessages,
    autoExtractEntities,
    defaultImportance,
    contextTokenBudget,
    consolidationEnabled,
    consolidationConfig
  )

  /**
   * Binary-compatible 5-parameter factory method.
   * Maintains backward compatibility for code compiled against pre-0.1.4 versions.
   */
  def apply(
    autoRecordMessages: Boolean,
    autoExtractEntities: Boolean,
    defaultImportance: Double,
    contextTokenBudget: Int,
    consolidationEnabled: Boolean
  ): MemoryManagerConfig = new MemoryManagerConfig(
    autoRecordMessages,
    autoExtractEntities,
    defaultImportance,
    contextTokenBudget,
    consolidationEnabled,
    ConsolidationConfig.default
  )

  /**
   * Default configuration.
   */
  val default: MemoryManagerConfig = new MemoryManagerConfig()

  /**
   * Configuration for testing.
   */
  val testing: MemoryManagerConfig = new MemoryManagerConfig(
    autoRecordMessages = false,
    autoExtractEntities = false
  )

  /**
   * Full-featured configuration.
   */
  val fullFeatured: MemoryManagerConfig = new MemoryManagerConfig(
    autoRecordMessages = true,
    autoExtractEntities = true,
    consolidationEnabled = true
  )
}
