package org.llm4s.agent.memory

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

private[memory] trait BaseMemoryManagerOps extends MemoryManager {

  def config: MemoryManagerConfig

  protected def withStore(updatedStore: MemoryStore): MemoryManager

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

    store.store(memory).map(withStore)
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

    store.store(memory).map(withStore)
  }

  override def recordUserFact(
    fact: String,
    userId: Option[String],
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .userFact(fact, userId)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(withStore)
  }

  override def recordKnowledge(
    content: String,
    source: String,
    metadata: Map[String, String]
  ): Result[MemoryManager] = {
    val memory = Memory
      .fromKnowledge(content, source)
      .withMetadata(metadata)

    store.store(memory).map(withStore)
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

    store.store(memory).map(withStore)
  }

  override def getRelevantContext(
    query: String,
    maxTokens: Int,
    filter: MemoryFilter
  ): Result[String] = {
    val approxMaxChars = maxTokens * 4
    store.search(query, topK = 20, filter).map(scored => formatMemoriesAsContext(scored.map(_.memory), approxMaxChars))
  }

  override def getConversationContext(
    conversationId: String,
    maxMessages: Int
  ): Result[String] =
    store.getConversation(conversationId, maxMessages).map { memories =>
      if (memories.isEmpty) {
        ""
      } else {
        val lines = memories.map { memory =>
          val role = memory.getMetadata("role").fold("unknown")(identity)
          s"[$role]: ${memory.content}"
        }
        s"Previous conversation:\n${lines.mkString("\n")}"
      }
    }

  override def getEntityContext(entityId: EntityId): Result[String] =
    store.getEntityMemories(entityId).map { memories =>
      if (memories.isEmpty) {
        ""
      } else {
        val entityName = memories.headOption
          .flatMap(_.getMetadata("entity_name"))
          .fold(entityId.value)(identity)

        val facts = memories.map(m => s"- ${m.content}")
        s"Known facts about $entityName:\n${facts.mkString("\n")}"
      }
    }

  override def getUserContext(userId: Option[String]): Result[String] = {
    val filter = userId match {
      case Some(id) =>
        MemoryFilter.ByType(MemoryType.UserFact) && MemoryFilter.ByMetadata("user_id", id)
      case None =>
        MemoryFilter.ByType(MemoryType.UserFact)
    }

    store.recall(filter).map { memories =>
      if (memories.isEmpty) {
        ""
      } else {
        val facts = memories.map(m => s"- ${m.content}")
        s"Known facts about the user:\n${facts.mkString("\n")}"
      }
    }
  }

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

  final protected def formatMemoriesAsContext(memories: Seq[Memory], maxChars: Int): String = {
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

    sections.get(MemoryType.Knowledge).foreach(addSection("Relevant Knowledge", _))
    sections.get(MemoryType.Entity).foreach(addSection("Entity Information", _))
    sections.get(MemoryType.UserFact).foreach(addSection("User Preferences", _))
    sections.get(MemoryType.Conversation).foreach(addSection("Previous Context", _))
    sections.get(MemoryType.Task).foreach(addSection("Past Tasks", _))

    sections.foreach {
      case (MemoryType.Custom(name), mems) => addSection(name, mems)
      case _                               =>
    }

    if (formatted.nonEmpty) {
      s"# Retrieved Context\n${formatted.toString.trim}"
    } else ""
  }
}
