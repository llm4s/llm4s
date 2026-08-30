package org.llm4s.agent.memory

import java.time.Instant

/**
 * Filter criteria for memory recall.
 *
 * MemoryFilters are composable predicates that can be combined
 * to create complex filtering logic for memory retrieval.
 */
sealed trait MemoryFilter {

  /**
   * Test if a memory matches this filter.
   * @param memory The memory to test
   * @return True if the memory matches the filter criteria, False otherwise
   */
  def matches(memory: Memory): Boolean

  /**
   * Combine this filter with another using AND logic.
   * @param other The other filter to combine with
   * @return A new MemoryFilter that matches if both filters match
   */
  def and(other: MemoryFilter): MemoryFilter =
    MemoryFilter.And(this, other)

  /**
   * Combine this filter with another using OR logic.
   * @param other The other filter to combine with
   * @return A new MemoryFilter that matches if either filter matches
   */
  def or(other: MemoryFilter): MemoryFilter =
    MemoryFilter.Or(this, other)

  /**
   * Negate this filter.
   * @return A new MemoryFilter that matches if this filter does not match
   */
  def not: MemoryFilter =
    MemoryFilter.Not(this)

  /**
   * Alias for `and`.
   */
  def &&(other: MemoryFilter): MemoryFilter = and(other)

  /**
   * Alias for `or`.
   */
  def ||(other: MemoryFilter): MemoryFilter = or(other)

  /**
   * Alias for `not`.
   */
  def unary_! : MemoryFilter = not
}

object MemoryFilter {

  /**
   * Match all memories (no filtering).
   */
  case object All extends MemoryFilter {
    def matches(memory: Memory): Boolean = true
  }

  /**
   * Match no memories.
   */
  case object None extends MemoryFilter {
    def matches(memory: Memory): Boolean = false
  }

  /**
   * Filter by memory type.
   */
  final case class ByType(memoryType: MemoryType) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.memoryType == memoryType
  }

  /**
   * Filter by multiple memory types (OR logic).
   */
  final case class ByTypes(memoryTypes: Set[MemoryType]) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memoryTypes.contains(memory.memoryType)
  }

  /**
   * Filter by metadata key-value pair.
   */
  final case class ByMetadata(key: String, value: String) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.metadata.get(key).contains(value)
  }

  /**
   * Filter by metadata key existence.
   */
  final case class HasMetadata(key: String) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.metadata.contains(key)
  }

  /**
   * Filter by metadata key containing substring.
   */
  final case class MetadataContains(key: String, substring: String) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.metadata.get(key).exists(_.contains(substring))
  }

  /**
   * Filter by entity ID.
   */
  final case class ByEntity(entityId: EntityId) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.metadata.get("entity_id").contains(entityId.value)
  }

  /**
   * Filter by conversation ID.
   */
  final case class ByConversation(conversationId: String) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.metadata.get("conversation_id").contains(conversationId)
  }

  /**
   * Filter by timestamp range.
   */
  final case class ByTimeRange(
    after: Option[Instant] = scala.None,
    before: Option[Instant] = scala.None
  ) extends MemoryFilter {
    def matches(memory: Memory): Boolean = {
      val afterOk  = after.forall(t => memory.timestamp.isAfter(t) || memory.timestamp.equals(t))
      val beforeOk = before.forall(t => memory.timestamp.isBefore(t) || memory.timestamp.equals(t))
      afterOk && beforeOk
    }
  }

  /**
   * Filter by minimum importance score.
   */
  final case class MinImportance(threshold: Double) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      memory.importance.exists(_ >= threshold)
  }

  /**
   * Filter by content containing substring.
   */
  final case class ContentContains(substring: String, caseSensitive: Boolean = false) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      if (caseSensitive) {
        memory.content.contains(substring)
      } else {
        memory.content.toLowerCase.contains(substring.toLowerCase)
      }
  }

  /**
   * Combine two filters with AND logic.
   */
  final case class And(left: MemoryFilter, right: MemoryFilter) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      left.matches(memory) && right.matches(memory)
  }

  /**
   * Combine two filters with OR logic.
   */
  final case class Or(left: MemoryFilter, right: MemoryFilter) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      left.matches(memory) || right.matches(memory)
  }

  /**
   * Negate a filter.
   */
  final case class Not(filter: MemoryFilter) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      !filter.matches(memory)
  }

  /**
   * Custom filter using a predicate function.
   */
  final case class Custom(predicate: Memory => Boolean) extends MemoryFilter {
    def matches(memory: Memory): Boolean =
      predicate(memory)
  }

  // Convenience constructors

  /**
   * Filter for conversation memories only.
   */
  def conversations: MemoryFilter = ByType(MemoryType.Conversation)

  /**
   * Filter for entity memories only.
   */
  def entities: MemoryFilter = ByType(MemoryType.Entity)

  /**
   * Filter for knowledge memories only.
   */
  def knowledge: MemoryFilter = ByType(MemoryType.Knowledge)

  /**
   * Filter for user fact memories only.
   */
  def userFacts: MemoryFilter = ByType(MemoryType.UserFact)

  /**
   * Filter for task memories only.
   */
  def tasks: MemoryFilter = ByType(MemoryType.Task)

  /**
   * Filter for memories after a given time.
   */
  def after(instant: Instant): MemoryFilter = ByTimeRange(after = Some(instant))

  /**
   * Filter for memories before a given time.
   */
  def before(instant: Instant): MemoryFilter = ByTimeRange(before = Some(instant))

  /**
   * Filter for memories within a time range.
   */
  def between(start: Instant, end: Instant): MemoryFilter =
    ByTimeRange(after = Some(start), before = Some(end))

  /**
   * Filter for memories from a specific conversation.
   */
  def forConversation(conversationId: String): MemoryFilter =
    ByConversation(conversationId)

  /**
   * Filter for memories about a specific entity.
   */
  def forEntity(entityId: EntityId): MemoryFilter =
    ByEntity(entityId)

  /**
   * Filter for memories with minimum importance.
   */
  def important(minScore: Double = 0.5): MemoryFilter =
    MinImportance(minScore)

  /**
   * Combine multiple filters with AND logic.
   */
  def all(filters: MemoryFilter*): MemoryFilter =
    filters.reduceOption(_ && _).getOrElse(All)

  /**
   * Combine multiple filters with OR logic.
   */
  def any(filters: MemoryFilter*): MemoryFilter =
    filters.reduceOption(_ || _).getOrElse(None)
}
