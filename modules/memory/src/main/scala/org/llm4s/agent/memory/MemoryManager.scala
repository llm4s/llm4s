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
   * @param maxTokens Approximate maximum tokens of context; when omitted, [[contextTokenBudget]]
   * @param filter Additional filter criteria
   * @return Formatted context string
   */
  def getRelevantContext(
    query: String,
    maxTokens: Int = contextTokenBudget,
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

  /**
   * The token budget `getRelevantContext` uses when a call passes no `maxTokens`.
   *
   * [[SimpleMemoryManager]] and [[LLMMemoryManager]] return their
   * `MemoryManagerConfig.contextTokenBudget`; other implementations get
   * [[MemoryManager.DefaultContextTokenBudget]] unless they override it.
   */
  def contextTokenBudget: Int = MemoryManager.DefaultContextTokenBudget
}

object MemoryManager {

  /** The context token budget used when neither the call nor the configuration sets one: 2000. */
  val DefaultContextTokenBudget: Int = 2000
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
 * A plain value built in code; this module reads no configuration file. Build one with
 * `MemoryManagerConfig(...)` (every option has a default) or a preset, and change it with the
 * `with*` setters; there is no public constructor or `copy`, so the type can gain options without
 * breaking callers. Every option is read by
 * the managers that extend the shared record/retrieve operations ([[SimpleMemoryManager]] and
 * [[LLMMemoryManager]]).
 *
 * @param autoExtractEntities When true, `recordMessage` (and so `recordConversation`) also runs
 *                            `extractEntities` over the content of each user and assistant
 *                            message it records, passing the conversation id. System and tool
 *                            messages are not analysed. Extraction is best-effort: if it fails,
 *                            the message stays recorded and the failure is logged as a warning.
 *                            A cancellation is not a failure: it is returned, so the caller stops.
 *                            [[SimpleMemoryManager]] extracts nothing, so the option only changes
 *                            what [[LLMMemoryManager]] stores (one extra LLM call per message).
 * @param defaultImportance Importance score given to memories recorded without one
 * @param contextTokenBudget Token budget `getRelevantContext` uses when the call passes no
 *                           `maxTokens`. An explicit `maxTokens` always wins.
 * @param consolidationConfig How `consolidateMemories` groups memories and handles errors
 */
final case class MemoryManagerConfig private (
  autoExtractEntities: Boolean,
  defaultImportance: Double,
  contextTokenBudget: Int,
  consolidationConfig: ConsolidationConfig
) {

  /** Set whether `recordMessage` also extracts entities from user and assistant messages. */
  def withAutoExtractEntities(enabled: Boolean): MemoryManagerConfig =
    copy(autoExtractEntities = enabled)

  /** Set the importance given to memories recorded without one. */
  def withDefaultImportance(importance: Double): MemoryManagerConfig =
    copy(defaultImportance = importance)

  /** Set the token budget `getRelevantContext` uses when the call passes no `maxTokens`. */
  def withContextTokenBudget(tokens: Int): MemoryManagerConfig =
    copy(contextTokenBudget = tokens)

  /** Set how `consolidateMemories` groups memories and handles errors. */
  def withConsolidationConfig(consolidation: ConsolidationConfig): MemoryManagerConfig =
    copy(consolidationConfig = consolidation)
}

/**
 * Configuration for memory consolidation behavior.
 *
 * Read by `consolidateMemories` whenever it is called. Nothing consolidates on its own: call
 * `consolidateMemories` when your application decides to.
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
   * Build a configuration; every option has a default.
   */
  def apply(
    autoExtractEntities: Boolean = false,
    defaultImportance: Double = 0.5,
    contextTokenBudget: Int = MemoryManager.DefaultContextTokenBudget,
    consolidationConfig: ConsolidationConfig = ConsolidationConfig.default
  ): MemoryManagerConfig = new MemoryManagerConfig(
    autoExtractEntities,
    defaultImportance,
    contextTokenBudget,
    consolidationConfig
  )

  /**
   * Default configuration.
   */
  val default: MemoryManagerConfig = MemoryManagerConfig()

  /**
   * Configuration for testing: no automatic entity extraction, so no LLM call is made unless a
   * test asks for one.
   */
  val testing: MemoryManagerConfig = MemoryManagerConfig(autoExtractEntities = false)

  /**
   * Full-featured configuration: entities are extracted from every recorded user and assistant
   * message. With [[LLMMemoryManager]] that is one extra LLM call per recorded user or assistant
   * message, on top of whatever the application itself sends.
   */
  val fullFeatured: MemoryManagerConfig = MemoryManagerConfig(autoExtractEntities = true)
}
