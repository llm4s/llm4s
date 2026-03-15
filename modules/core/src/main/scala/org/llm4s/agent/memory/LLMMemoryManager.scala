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
   * Uses client.getContextBudget() to dynamically limit the group size based on token count
   * to prevent context window overflow during summarization. The token budget is also
   * capped by config.maxMemoriesPerGroup as a secondary limit.
   *
   * Note: minCount is applied after budget capping. A group whose members all exceed the
   * token budget individually will be skipped (the first memory is always included to avoid
   * empty groups, but subsequent oversized memories are dropped).
   *
   * @param memories Memories to group
   * @param minCount Minimum memories required per group for consolidation
   */
  private def groupMemoriesForConsolidation(
    memories: Seq[Memory],
    minCount: Int
  ): Seq[Seq[Memory]] = {
    // getContextBudget() already applies HeadroomPercent.Standard (~8% headroom)
    val tokenBudget = client.getContextBudget()
    val maxPerGroup = config.consolidationConfig.maxMemoriesPerGroup

    /**
     * Take memories until the token budget is exhausted, using ~4 chars/token heuristic.
     * Always includes the first memory to avoid empty groups for oversized items.
     * Also caps at maxMemoriesPerGroup as a secondary limit.
     */
    def takeUntilBudget(mems: Seq[Memory]): Seq[Memory] = {
      val capped = mems.take(maxPerGroup)
      if (capped.isEmpty) return Seq.empty
      // Always include the first memory, then accumulate until budget is reached
      val first           = capped.head
      val firstTokens     = first.content.length / 4
      val remainingBudget = tokenBudget - firstTokens
      val rest = capped.tail
        .scanLeft(0)((acc, m) => acc + m.content.length / 4)
        .drop(1)
        .zip(capped.tail)
        .takeWhile(_._1 <= remainingBudget)
        .map(_._2)
      first +: rest
    }

    // Group by conversation (only Conversation type, sorted by timestamp for stable summaries)
    val byConversation = memories
      .filter(_.memoryType == MemoryType.Conversation)
      .filter(_.conversationId.isDefined)
      .groupBy(_.metadata.getOrElse("conversation_id", ""))
      .filter(_._1.nonEmpty)
      .values
      .map(group => takeUntilBudget(group.toSeq.sortBy(_.timestamp)))
      .filter(_.length >= minCount)
      .toSeq

    // Group by entity
    val byEntity = memories
      .filter(_.memoryType == MemoryType.Entity)
      .groupBy(_.getMetadata("entity_id"))
      .collect { case (Some(_), facts) => takeUntilBudget(facts.toSeq) }
      .filter(_.length >= minCount)
      .toSeq

    // Group user facts by user ID
    val byUser = memories
      .filter(_.memoryType == MemoryType.UserFact)
      .groupBy(_.getMetadata("user_id"))
      .values
      .map(group => takeUntilBudget(group.toSeq))
      .filter(_.length >= minCount)
      .toSeq

    // Group knowledge by source
    val byKnowledge = memories
      .filter(_.memoryType == MemoryType.Knowledge)
      .groupBy(_.source)
      .collect { case (Some(_), entries) => takeUntilBudget(entries.toSeq) }
      .filter(_.length >= minCount)
      .toSeq

    // Group tasks by success status
    val byTask = memories
      .filter(_.memoryType == MemoryType.Task)
      .groupBy(_.getMetadata("success").getOrElse("unknown"))
      .values
      .map(group => takeUntilBudget(group.toSeq))
      .filter(_.length >= minCount)
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
