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
  config: MemoryManagerConfig,
  override val store: MemoryStore,
  client: LLMClient
) extends MemoryManager {

  private val logger = LoggerFactory.getLogger(getClass)

  // ============================================================
  // Recording methods (same as SimpleMemoryManager)
  // ============================================================

  override def recordMessage(
    message: Message,
    conversationId: String,
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val role = message match {
      case _: UserMessage      => "user"
      case _: AssistantMessage => "assistant"
      case _: SystemMessage    => "system"
      case _: ToolMessage      => "tool"
    }

    val memory = Memory
      .fromConversation(message.content, role, Some(conversationId))
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordConversation(
    messages: Seq[Message],
    conversationId: String
  ): Result[MemoryManager] =
    messages.foldLeft[Result[MemoryManager]](Right(this)) { (acc, message) =>
      acc.flatMap(_.recordMessage(message, conversationId, None))
    }

  override def recordEntityFact(
    entityId: EntityId,
    entityName: String,
    fact: String,
    entityType: String,
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .forEntity(entityId, entityName, fact, entityType)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordUserFact(
    fact: String,
    userId: Option[String],
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .userFact(fact, userId)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordKnowledge(
    content: String,
    source: String,
    metadata: Map[String, String]
  ): Result[MemoryManager] = {
    val memory = Memory
      .fromKnowledge(content, source)
      .withMetadata(metadata)

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordTask(
    description: String,
    outcome: String,
    success: Boolean,
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .fromTask(description, outcome, success)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  // ============================================================
  // Context retrieval methods (same as SimpleMemoryManager)
  // ============================================================

  override def getRelevantContext(
    query: String,
    maxTokens: Int,
    filter: MemoryFilter
  ): Result[String] = {
    // Estimate ~4 chars per token
    val approxMaxChars = maxTokens * 4

    for {
      scored <- store.search(query, topK = 20, filter)
    } yield formatMemoriesAsContext(scored.map(_.memory), approxMaxChars)
  }

  override def getConversationContext(
    conversationId: String,
    maxMessages: Int
  ): Result[String] =
    for {
      memories <- store.getConversation(conversationId, maxMessages)
    } yield
      if (memories.isEmpty) {
        ""
      } else {
        val lines = memories.map { memory =>
          val role = memory.getMetadata("role").getOrElse("unknown")
          s"[$role]: ${memory.content}"
        }
        s"Previous conversation:\n${lines.mkString("\n")}"
      }

  override def getEntityContext(entityId: EntityId): Result[String] =
    for {
      memories <- store.getEntityMemories(entityId)
    } yield
      if (memories.isEmpty) {
        ""
      } else {
        val entityName = memories.headOption
          .flatMap(_.getMetadata("entity_name"))
          .getOrElse(entityId.value)

        val facts = memories.map(m => s"- ${m.content}")
        s"Known facts about $entityName:\n${facts.mkString("\n")}"
      }

  override def getUserContext(userId: Option[String]): Result[String] = {
    val filter = userId match {
      case Some(id) =>
        MemoryFilter.ByType(MemoryType.UserFact) && MemoryFilter.ByMetadata("user_id", id)
      case None =>
        MemoryFilter.ByType(MemoryType.UserFact)
    }

    for {
      memories <- store.recall(filter)
    } yield
      if (memories.isEmpty) {
        ""
      } else {
        val facts = memories.map(m => s"- ${m.content}")
        s"Known facts about the user:\n${facts.mkString("\n")}"
      }
  }

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

  // ============================================================
  // Statistics
  // ============================================================

  override def stats: Result[MemoryStats] =
    for {
      total             <- store.count()
      conversationCount <- store.count(MemoryFilter.conversations)
      entityCount       <- store.count(MemoryFilter.entities)
      knowledgeCount    <- store.count(MemoryFilter.knowledge)
      userFactCount     <- store.count(MemoryFilter.userFacts)
      taskCount         <- store.count(MemoryFilter.tasks)
      allMemories       <- store.recall(MemoryFilter.All, Int.MaxValue)
    } yield {
      val byType = Map[MemoryType, Long](
        MemoryType.Conversation -> conversationCount,
        MemoryType.Entity       -> entityCount,
        MemoryType.Knowledge    -> knowledgeCount,
        MemoryType.UserFact     -> userFactCount,
        MemoryType.Task         -> taskCount
      ).filter(_._2 > 0)

      val timestamps = allMemories.map(_.timestamp)
      val embedded   = allMemories.count(_.isEmbedded)

      val distinctEntities = allMemories
        .flatMap(_.getMetadata("entity_id"))
        .distinct
        .size

      val distinctConversations = allMemories
        .flatMap(_.getMetadata("conversation_id"))
        .distinct
        .size

      MemoryStats(
        totalMemories = total,
        byType = byType,
        entityCount = distinctEntities.toLong,
        conversationCount = distinctConversations.toLong,
        embeddedCount = embedded.toLong,
        oldestMemory = if (timestamps.isEmpty) None else Some(timestamps.min),
        newestMemory = if (timestamps.isEmpty) None else Some(timestamps.max)
      )
    }

  /**
   * Format memories as context string.
   */
  private def formatMemoriesAsContext(memories: Seq[Memory], maxChars: Int): String = {
    if (memories.isEmpty) return ""

    val sections      = memories.groupBy(_.memoryType)
    val formatted     = new StringBuilder()
    var currentLength = 0

    def addSection(title: String, mems: Seq[Memory]): Unit =
      if (mems.nonEmpty && currentLength < maxChars) {
        val header = s"\n## $title\n"
        formatted.append(header)
        currentLength += header.length

        mems.takeWhile { memory =>
          val line = s"- ${memory.content}\n"
          if (currentLength + line.length <= maxChars) {
            formatted.append(line)
            currentLength += line.length
            true
          } else false
        }
      }

    // Order sections by relevance
    sections.get(MemoryType.Knowledge).foreach(addSection("Relevant Knowledge", _))
    sections.get(MemoryType.Entity).foreach(addSection("Entity Information", _))
    sections.get(MemoryType.UserFact).foreach(addSection("User Preferences", _))
    sections.get(MemoryType.Conversation).foreach(addSection("Previous Context", _))
    sections.get(MemoryType.Task).foreach(addSection("Past Tasks", _))

    // Handle custom types
    sections.foreach {
      case (MemoryType.Custom(name), mems) => addSection(s"$name", mems)
      case _                               => // already handled
    }

    if (formatted.nonEmpty) {
      s"# Retrieved Context\n${formatted.toString.trim}"
    } else ""
  }
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
