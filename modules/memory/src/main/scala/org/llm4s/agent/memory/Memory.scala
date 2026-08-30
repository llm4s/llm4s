package org.llm4s.agent.memory

import java.time.Instant
import java.util.UUID

/**
 * Type-safe wrapper for memory identifiers.
 */
final case class MemoryId(value: String) extends AnyVal {
  override def toString: String = value
}

object MemoryId {

  /**
   * Generate a new unique memory ID.
   */
  def generate(): MemoryId = MemoryId(UUID.randomUUID().toString)

  /**
   * Create a memory ID from a string.
   */
  def apply(value: String): MemoryId = new MemoryId(value)
}

/**
 * Type-safe wrapper for entity identifiers.
 */
final case class EntityId(value: String) extends AnyVal {
  override def toString: String = value
}

object EntityId {

  /**
   * Generate a new unique entity ID.
   */
  def generate(): EntityId = EntityId(UUID.randomUUID().toString)

  /**
   * Create an entity ID from a name (normalized).
   */
  def fromName(name: String): EntityId =
    EntityId(name.toLowerCase.replaceAll("\\s+", "_"))
}

/**
 * Classification of memory types.
 *
 * Different memory types enable different retrieval strategies
 * and allow for targeted filtering during recall.
 */
sealed trait MemoryType {
  def name: String
}

object MemoryType {

  /**
   * Memories from conversation history.
   * These are automatically captured from agent interactions.
   */
  case object Conversation extends MemoryType {
    val name = "conversation"
  }

  /**
   * Facts about entities (people, organizations, concepts).
   * Tracked across conversations for contextual awareness.
   */
  case object Entity extends MemoryType {
    val name = "entity"
  }

  /**
   * Knowledge from external documents or knowledge bases.
   * Used for RAG-style retrieval.
   */
  case object Knowledge extends MemoryType {
    val name = "knowledge"
  }

  /**
   * Facts learned about the user.
   * Preferences, background, prior interactions.
   */
  case object UserFact extends MemoryType {
    val name = "user_fact"
  }

  /**
   * Memories of completed tasks and their outcomes.
   * Useful for learning from past executions.
   */
  case object Task extends MemoryType {
    val name = "task"
  }

  /**
   * Custom memory type for domain-specific use cases.
   */
  final case class Custom(name: String) extends MemoryType

  /**
   * All built-in memory types.
   */
  val builtIn: Set[MemoryType] = Set(Conversation, Entity, Knowledge, UserFact, Task)

  /**
   * Parse a memory type from string.
   */
  def fromString(s: String): MemoryType = s.toLowerCase match {
    case "conversation" => Conversation
    case "entity"       => Entity
    case "knowledge"    => Knowledge
    case "user_fact"    => UserFact
    case "task"         => Task
    case other          => Custom(other)
  }
}

/**
 * A single memory entry in the memory system.
 *
 * Memories are immutable records that capture information
 * for later retrieval. They include content, metadata for
 * filtering, and timestamps for recency-based retrieval.
 *
 * @param id Unique identifier for this memory
 * @param content The actual content/text of the memory
 * @param memoryType Classification of the memory
 * @param metadata Key-value pairs for filtering and context
 * @param timestamp When this memory was created
 * @param importance Optional importance score (0.0 to 1.0) for prioritization.
 * 1.0 represents critical facts, while 0 represents trivial details.
 * If None, system treats it as neutral importance.
 * @param embedding Optional pre-computed embedding vector for semantic search
 */
final case class Memory(
  id: MemoryId,
  content: String,
  memoryType: MemoryType,
  metadata: Map[String, String] = Map.empty,
  timestamp: Instant = Instant.now(),
  importance: Option[Double] = None,
  embedding: Option[Array[Float]] = None
) {

  /**
   * Create a copy with updated metadata.
   */
  def withMetadata(key: String, value: String): Memory =
    copy(metadata = metadata + (key -> value))

  /**
   * Create a copy with merged metadata.
   */
  def withMetadata(newMetadata: Map[String, String]): Memory =
    copy(metadata = metadata ++ newMetadata)

  /**
   * Create a copy with an importance score.
   */
  def withImportance(score: Double): Memory =
    copy(importance = Some(math.max(0.0, math.min(1.0, score))))

  /**
   * Create a copy with an embedding vector.
   */
  def withEmbedding(vector: Array[Float]): Memory =
    copy(embedding = Some(vector))

  /**
   * Check if this memory has been embedded.
   */
  def isEmbedded: Boolean = embedding.isDefined

  /**
   * Get metadata value by key.
   */
  def getMetadata(key: String): Option[String] = metadata.get(key)

  /**
   * Get the source of this memory (if recorded).
   */
  def source: Option[String] = getMetadata("source")

  /**
   * Get the conversation ID this memory belongs to (if any).
   */
  def conversationId: Option[String] = getMetadata("conversation_id")

  /**
   * Get the agent ID that created this memory (if any).
   */
  def agentId: Option[String] = getMetadata("agent_id")
}

object Memory {

  /**
   * Create a conversation memory from a message.
   * @param content The message content
   * @param role The role of the message sender (e.g., "user", "assistant")
   * @param conversationId Optional conversation ID of the conversation session
   * @return A new Memory instance representing this conversation message
   */
  def fromConversation(
    content: String,
    role: String,
    conversationId: Option[String] = None
  ): Memory = Memory(
    id = MemoryId.generate(),
    content = content,
    memoryType = MemoryType.Conversation,
    metadata = Map("role" -> role) ++ conversationId.map("conversation_id" -> _)
  )

  /**
   * Create an entity memory.
   */
  def forEntity(
    entityId: EntityId,
    entityName: String,
    fact: String,
    entityType: String = "unknown"
  ): Memory = Memory(
    id = MemoryId.generate(),
    content = fact,
    memoryType = MemoryType.Entity,
    metadata = Map(
      "entity_id"   -> entityId.value,
      "entity_name" -> entityName,
      "entity_type" -> entityType
    )
  )

  /**
   * Create a knowledge memory from a document chunk.
   */
  def fromKnowledge(
    content: String,
    source: String,
    chunkIndex: Option[Int] = None
  ): Memory = Memory(
    id = MemoryId.generate(),
    content = content,
    memoryType = MemoryType.Knowledge,
    metadata = Map("source" -> source) ++ chunkIndex.map(i => "chunk_index" -> i.toString)
  )

  /**
   * Create a user fact memory.
   */
  def userFact(
    fact: String,
    userId: Option[String] = None
  ): Memory = Memory(
    id = MemoryId.generate(),
    content = fact,
    memoryType = MemoryType.UserFact,
    metadata = userId.map("user_id" -> _).toMap
  )

  /**
   * Create a task completion memory.
   */
  def fromTask(
    taskDescription: String,
    outcome: String,
    success: Boolean
  ): Memory = Memory(
    id = MemoryId.generate(),
    content = s"Task: $taskDescription\nOutcome: $outcome",
    memoryType = MemoryType.Task,
    metadata = Map("success" -> success.toString)
  )
}
