package org.llm4s.agent.memory

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * Memory manager with LLM-powered consolidation and entity extraction.
 *
 * Extends basic memory management with advanced features:
 * - Automatic memory consolidation using LLM summarization
 * - Entity extraction from conversation text (TODO: Phase 2)
 * - Importance scoring based on content analysis (TODO: Phase 2)
 *
 * This implementation follows the same patterns as SimpleMemoryManager
 * but adds LLM-powered intelligence for memory operations.
 *
 * @param config Configuration for memory management
 * @param store Underlying memory store
 * @param client LLM client for consolidation and extraction
 */
final case class LLMMemoryManager(
  override val config: MemoryManagerConfig,
  override val store: MemoryStore,
  client: LLMClient
) extends BaseMemoryManagerOps {

  private val logger = LoggerFactory.getLogger(getClass)

  // ============================================================
  // LLM-powered consolidation (NEW IMPLEMENTATION)
  // ============================================================

  override def consolidateMemories(
    olderThan: Instant,
    minCount: Int
  ): Result[MemoryManager] =
    // 1. Find old memories that need consolidation
    store
      .recall(
        filter = MemoryFilter.before(olderThan),
        limit = Int.MaxValue
      )
      .flatMap { oldMemories =>
        // 2. Group memories by type and context, applying minCount per group
        val grouped = groupMemoriesForConsolidation(oldMemories, minCount)

        // 3. Consolidate each group (strict mode fails fast, best-effort logs and continues)
        grouped
          .foldLeft[Result[MemoryStore]](Right(store)) { case (accStore, group) =>
            accStore.flatMap { s =>
              consolidateGroup(group, s) match {
                case Right(newStore) => Right(newStore)
                case Left(error)     =>
                  // Log error with safe summary (no sensitive content)
                  val groupType = group.headOption.map(_.memoryType.name).getOrElse("unknown")
                  val groupSize = group.length
                  val groupIds  = group.map(_.id.value.take(8)).mkString(", ")
                  logger.warn(
                    s"Consolidation failed for $groupType group (size=$groupSize, ids=[$groupIds]): ${error.message}"
                  )

                  // Strict mode: fail fast. Best-effort mode: continue with current store
                  if (config.consolidationConfig.strictMode) Left(error)
                  else Right(s)
              }
            }
          }
          .map(consolidatedStore => copy(store = consolidatedStore))
      }

  /**
   * Group memories for consolidation.
   *
   * Groups by:
   * - Conversation ID (consolidate entire conversations)
   * - Entity ID (consolidate entity facts)
   * - User ID (consolidate user facts)
   * - Knowledge source (consolidate knowledge from same source)
   * - Task success status (consolidate successful/failed tasks separately)
   *
   * Only groups with minCount+ memories are returned.
   * Caps each group at config.maxMemoriesPerGroup to prevent context overflow.
   *
   * TODO: Use client.getContextWindow() for more accurate token budget management
   * instead of relying on maxMemoriesPerGroup as a proxy.
   *
   * @param memories Memories to group
   * @param minCount Minimum memories required per group for consolidation
   */
  private def groupMemoriesForConsolidation(
    memories: Seq[Memory],
    minCount: Int
  ): Seq[Seq[Memory]] = {
    // Group by conversation (only Conversation type, sorted by timestamp for stable summaries)
    val byConversation = memories
      .filter(_.memoryType == MemoryType.Conversation)
      .filter(_.conversationId.isDefined)
      .groupBy(_.conversationId.get)
      .values
      .filter(_.length >= minCount) // Apply minCount per group
      .map(group =>
        group
          .sortBy(_.timestamp) // Sort by timestamp (oldest first)
          .take(config.consolidationConfig.maxMemoriesPerGroup)
      ) // Cap group size
      .toSeq

    // Group by entity
    val byEntity = memories
      .filter(_.memoryType == MemoryType.Entity)
      .groupBy(_.getMetadata("entity_id"))
      .collect {
        case (Some(_), facts) if facts.length >= minCount => facts.take(config.consolidationConfig.maxMemoriesPerGroup)
      }
      .toSeq

    // Group user facts by user ID
    val byUser = memories
      .filter(_.memoryType == MemoryType.UserFact)
      .groupBy(_.getMetadata("user_id"))
      .values
      .filter(_.length >= minCount)                                // Apply minCount per group
      .map(_.take(config.consolidationConfig.maxMemoriesPerGroup)) // Cap group size
      .toSeq

    // Group knowledge by source
    val byKnowledge = memories
      .filter(_.memoryType == MemoryType.Knowledge)
      .groupBy(_.source)
      .collect {
        case (Some(_), entries) if entries.length >= minCount =>
          entries.take(config.consolidationConfig.maxMemoriesPerGroup)
      }
      .toSeq

    // Group tasks by success status
    val byTask = memories
      .filter(_.memoryType == MemoryType.Task)
      .groupBy(_.getMetadata("success").getOrElse("unknown"))
      .values
      .filter(_.length >= minCount)                                // Apply minCount per group
      .map(_.take(config.consolidationConfig.maxMemoriesPerGroup)) // Cap group size
      .toSeq

    byConversation ++ byEntity ++ byUser ++ byKnowledge ++ byTask
  }

  /**
   * Consolidate a single group of memories.
   *
   * Uses LLM to generate a summary, then replaces the group
   * with a single consolidated memory.
   */
  private def consolidateGroup(
    group: Seq[Memory],
    currentStore: MemoryStore
  ): Result[MemoryStore] = {
    if (group.isEmpty) return Right(currentStore)

    // 1. Determine consolidation prompt based on memory type
    val userPrompt = selectPromptForGroup(group)

    // 2. Call LLM with system prompt for security + user prompt
    val completionResult = client.complete(
      conversation = Conversation(
        Seq(
          SystemMessage(ConsolidationPrompts.systemPrompt),
          UserMessage(userPrompt)
        )
      ),
      options = CompletionOptions(
        maxTokens = Some(500), // Cap output length for stable summaries
        temperature = 0.3      // Low temperature for consistent, factual summaries
      )
    )

    completionResult.flatMap { completion =>
      val consolidatedText = completion.content.trim

      // 3. Validate output
      if (consolidatedText.isEmpty) {
        Left(
          org.llm4s.error.ValidationError(
            "consolidation_output",
            "Consolidation produced empty output"
          )
        )
      } else {
        // Cap consolidated text length (sanity check)
        val cappedText = if (consolidatedText.length > 2000) {
          logger.warn(
            s"Consolidation output too long (${consolidatedText.length} chars), truncating to 2000"
          )
          consolidatedText.take(2000) + "..."
        } else consolidatedText

        // 4. Create consolidated memory
        val consolidatedMemory = Memory(
          id = MemoryId.generate(),
          content = cappedText,
          memoryType = group.head.memoryType,
          metadata = mergeMetadata(group),
          timestamp = group.map(_.timestamp).max,
          importance = group.flatMap(_.importance).maxOption,
          embedding = None // Will be regenerated if needed
        )

        // 5. Store consolidated memory first, then delete originals
        // This prevents data loss if delete succeeds but store fails
        currentStore.store(consolidatedMemory).flatMap { updatedStore =>
          group.foldLeft[Result[MemoryStore]](Right(updatedStore)) { case (accStore, memory) =>
            accStore.flatMap(_.delete(memory.id))
          }
        }
      }
    }
  }

  /**
   * Select the appropriate consolidation prompt for a memory group.
   */
  private def selectPromptForGroup(group: Seq[Memory]): String =
    group.head.memoryType match {
      case MemoryType.Conversation =>
        ConsolidationPrompts.conversationSummary(group)

      case MemoryType.Entity =>
        val entityName = group.head.getMetadata("entity_name").getOrElse("Unknown")
        ConsolidationPrompts.entityConsolidation(entityName, group)

      case MemoryType.Knowledge =>
        ConsolidationPrompts.knowledgeConsolidation(group)

      case MemoryType.UserFact =>
        val userId = group.head.getMetadata("user_id")
        ConsolidationPrompts.userFactConsolidation(userId, group)

      case MemoryType.Task =>
        ConsolidationPrompts.taskConsolidation(group)

      case MemoryType.Custom(_) =>
        ConsolidationPrompts.knowledgeConsolidation(group)
    }

  /**
   * Merge metadata from multiple memories.
   *
   * Collects all unique key-value pairs across memories. For keys that appear
   * in multiple memories with different values, keeps the first occurrence.
   * Adds consolidation tracking metadata.
   */
  private def mergeMetadata(memories: Seq[Memory]): Map[String, String] = {
    // Merge all metadata, keeping first value for conflicting keys
    val mergedMetadata = memories.foldLeft(Map.empty[String, String]) { (acc, memory) =>
      memory.metadata.foldLeft(acc) { case (m, (key, value)) =>
        if (m.contains(key)) m else m + (key -> value)
      }
    }

    // Add consolidation metadata
    mergedMetadata ++ Map(
      "consolidated_from"    -> memories.length.toString,
      "consolidated_at"      -> Instant.now().toString,
      "original_ids"         -> memories.map(_.id.value).take(10).mkString(","),
      "consolidation_method" -> "llm_summary"
    )
  }

  // ============================================================
  // Entity extraction (TODO: Future implementation)
  // ============================================================

  override def extractEntities(
    text: String,
    conversationId: Option[String]
  ): Result[MemoryManager] =
    // TODO: Implement LLM-based entity extraction
    // For now, return unchanged
    Right(this)

  override protected def withStore(updatedStore: MemoryStore): MemoryManager =
    copy(store = updatedStore)
}

object LLMMemoryManager {

  /**
   * Create a new LLM-powered memory manager.
   */
  def apply(
    config: MemoryManagerConfig,
    store: MemoryStore,
    client: LLMClient
  ): LLMMemoryManager =
    new LLMMemoryManager(config, store, client)

  /**
   * Create with default configuration.
   */
  def withDefaults(store: MemoryStore, client: LLMClient): LLMMemoryManager =
    new LLMMemoryManager(MemoryManagerConfig.default, store, client)

  /**
   * Create with in-memory store for testing.
   */
  def forTesting(client: LLMClient): LLMMemoryManager =
    new LLMMemoryManager(
      MemoryManagerConfig.testing,
      InMemoryStore.forTesting(),
      client
    )
}
