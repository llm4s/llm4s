package org.llm4s.agent.memory

/**
 * LLM prompts for memory consolidation.
 *
 * This object provides prompt templates for consolidating different
 * types of memories using LLM summarization.
 */
object ConsolidationPrompts {

  /**
   * Prompt for consolidating conversation memories.
   *
   * Takes multiple conversation turns and creates a single summary
   * preserving key facts and decisions.
   *
   * @param memories Conversation messages to consolidate
   * @return Prompt string for LLM
   */
  def conversationSummary(memories: Seq[Memory]): String = {
    val memoryTexts = memories.zipWithIndex.map { case (m, idx) =>
      val role = m.getMetadata("role").getOrElse("unknown")
      s"${idx + 1}. [$role]: ${m.content}"
    }.mkString("\n")

    s"""Summarize the following conversation into a single concise paragraph.
       |Preserve key facts, decisions, and important details.
       |Remove redundancy and small talk.
       |
       |Conversation:
       |$memoryTexts
       |
       |Summary (1-3 sentences):""".stripMargin
  }

  /**
   * Prompt for consolidating entity facts.
   *
   * Takes multiple facts about an entity and creates a comprehensive
   * description preserving all important information.
   *
   * @param entityName Name of the entity
   * @param facts Entity facts to consolidate
   * @return Prompt string for LLM
   */
  def entityConsolidation(entityName: String, facts: Seq[Memory]): String = {
    val factTexts = facts.map(m => s"- ${m.content}").mkString("\n")

    s"""Consolidate these facts about "$entityName" into a single comprehensive description.
       |Preserve all important information and remove redundancy.
       |
       |Facts:
       |$factTexts
       |
       |Consolidated description:""".stripMargin
  }

  /**
   * Prompt for consolidating knowledge entries.
   *
   * Takes multiple related knowledge entries and creates a unified entry
   * preserving all important information.
   *
   * @param memories Knowledge entries to consolidate
   * @return Prompt string for LLM
   */
  def knowledgeConsolidation(memories: Seq[Memory]): String = {
    val knowledgeTexts = memories.zipWithIndex.map { case (m, idx) =>
      val source = m.source.getOrElse("unknown")
      s"${idx + 1}. [from $source]: ${m.content}"
    }.mkString("\n")

    s"""Consolidate these related knowledge entries into a single comprehensive entry.
       |Preserve all important information and remove redundancy.
       |
       |Knowledge entries:
       |$knowledgeTexts
       |
       |Consolidated knowledge:""".stripMargin
  }

  /**
   * Prompt for consolidating user facts.
   *
   * Takes multiple user facts and creates a unified user profile
   * preserving all preferences and information.
   *
   * @param userId Optional user identifier
   * @param facts User facts to consolidate
   * @return Prompt string for LLM
   */
  def userFactConsolidation(userId: Option[String], facts: Seq[Memory]): String = {
    val userLabel = userId.map(id => s"user $id").getOrElse("the user")
    val factTexts = facts.map(m => s"- ${m.content}").mkString("\n")

    s"""Consolidate these facts about $userLabel into a single comprehensive user profile.
       |Preserve all important preferences and information.
       |
       |Facts:
       |$factTexts
       |
       |Consolidated profile:""".stripMargin
  }

  /**
   * Prompt for consolidating task memories.
   *
   * Takes multiple task completion records and creates a summary
   * of outcomes and learnings.
   *
   * @param tasks Task memories to consolidate
   * @return Prompt string for LLM
   */
  def taskConsolidation(tasks: Seq[Memory]): String = {
    val taskTexts = tasks.zipWithIndex.map { case (m, idx) =>
      val success = m.getMetadata("success").getOrElse("unknown")
      s"${idx + 1}. [success: $success] ${m.content}"
    }.mkString("\n")

    s"""Consolidate these task completion records into a single summary.
       |Focus on outcomes, patterns, and learnings.
       |
       |Tasks:
       |$taskTexts
       |
       |Consolidated summary:""".stripMargin
  }
}
