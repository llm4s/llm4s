package org.llm4s.agent.memory

/**
 * Where a consolidation's summary came from, as its metadata records it (#3035).
 *
 * [[LLMMemoryManager]] consolidates only memories that share one scope: every metadata value except the
 * [[DescriptiveKeys]], which may differ from memory to memory. So a summary carries the same `conversation_id`,
 * `user_id`, `entity_id`, `source`, writer (`llm4s.writer`) and any key of the application's own as each of its
 * sources, and an erasure by any of them (`MemoryStore.deleteMatching`) removes the summary with the rest. A
 * descriptive key is kept on the summary only when every source has the same value.
 *
 * The summary also records [[ConsolidatedFrom]] (how many memories it replaced), [[ConsolidatedAt]],
 * [[ConsolidationMethod]], [[OriginalIds]] (every memory it stands for, through earlier summaries too) and
 * [[Version]]. These keys are a stored format.
 *
 * A summary written before #3035 has no [[Version]]: its scope is the first source's, so it may hold the content of
 * memories in other conversations or of other users. It is never consolidated again; [[legacySummaries]] finds such
 * summaries, to review or delete them.
 */
object ConsolidationProvenance {

  /** How many memories the summary replaced. */
  val ConsolidatedFrom: String = "consolidated_from"

  /** When the summary was written, an ISO-8601 instant. */
  val ConsolidatedAt: String = "consolidated_at"

  /** How the summary was made: `llm_summary`. */
  val ConsolidationMethod: String = "consolidation_method"

  /**
   * The ids of every memory the summary stands for, comma-separated: its sources', and, for a source that was itself a
   * summary, that summary's own `original_ids`.
   */
  val OriginalIds: String = "original_ids"

  /** The provenance format: `2` since #3035. A summary without it was written before. */
  val Version: String = "consolidation_version"

  /** The current [[Version]]. */
  val CurrentVersion: String = "2"

  /** The [[ConsolidationMethod]] of [[LLMMemoryManager]]'s summaries. */
  val LlmSummary: String = "llm_summary"

  /** The bookkeeping keys above: never part of a scope, and set afresh on each summary. */
  val BookkeepingKeys: Set[String] = Set(ConsolidatedFrom, ConsolidatedAt, ConsolidationMethod, OriginalIds, Version)

  /**
   * Keys that describe one memory rather than whom or what it belongs to, so memories that differ in them may still
   * be consolidated together: a message's `role`, a knowledge chunk's `chunk_index`, an entity's `entity_name` and
   * `entity_type`, and the [[BookkeepingKeys]]. Every other metadata key is part of a memory's scope.
   */
  val DescriptiveKeys: Set[String] = Set("role", "chunk_index", "entity_name", "entity_type") ++ BookkeepingKeys

  /** `memory`'s scope: its metadata without the [[DescriptiveKeys]]. Only memories with equal scopes are merged. */
  def scopeOf(memory: Memory): Map[String, String] = memory.metadata.filterNot { case (k, _) => DescriptiveKeys(k) }

  /** Whether `memory` is a consolidation's summary, of any version. */
  def isSummary(memory: Memory): Boolean = memory.metadata.contains(ConsolidationMethod)

  /** Whether `memory` is a summary written before #3035, without provenance. */
  def isLegacySummary(memory: Memory): Boolean = isSummary(memory) && !memory.metadata.contains(Version)

  /** The ids `memory` stands for: its [[OriginalIds]] if it is a current summary, else its own id. */
  def sourceIds(memory: Memory): Seq[MemoryId] =
    if (isSummary(memory) && !isLegacySummary(memory))
      memory.getMetadata(OriginalIds).toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).map(MemoryId(_))
    else Seq(memory.id)

  /** Summaries written before #3035: a consolidation method and no [[Version]]. */
  val legacySummaries: MemoryFilter =
    MemoryFilter.HasMetadata(ConsolidationMethod).and(MemoryFilter.HasMetadata(Version).not)
}
