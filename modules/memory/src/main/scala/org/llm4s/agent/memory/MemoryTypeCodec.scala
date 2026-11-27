package org.llm4s.agent.memory

/**
 * How the vector and Postgres stores write a [[MemoryType]] to their `memory_type` column, as `SQLiteMemoryStore`
 * does: a built-in type as its name, a custom type as `custom:<name>`, keeping its case. The prefix keeps a custom
 * type apart from a built-in one of the same name, so `Custom("task")` does not come back as `Task`.
 *
 * Rows written before the prefix hold a custom type's bare name; they read back as that custom type, with its case.
 */
private[memory] object MemoryTypeCodec {

  private val CustomPrefix = "custom:"

  private val BuiltInNames: Set[String] = MemoryType.builtIn.map(_.name)

  def encode(memoryType: MemoryType): String = memoryType match {
    case MemoryType.Custom(name) => CustomPrefix + name
    case builtIn                 => builtIn.name
  }

  def decode(stored: String): MemoryType = stored match {
    case "conversation"                            => MemoryType.Conversation
    case "entity"                                  => MemoryType.Entity
    case "knowledge"                               => MemoryType.Knowledge
    case "user_fact"                               => MemoryType.UserFact
    case "task"                                    => MemoryType.Task
    case custom if custom.startsWith(CustomPrefix) => MemoryType.Custom(custom.stripPrefix(CustomPrefix))
    case legacy                                    => MemoryType.Custom(legacy) // written before the prefix
  }

  /**
   * Every stored spelling that [[decode]] reads as `memoryType`: its [[encode]]d form and, for a custom type, the bare
   * name an older store wrote, unless that name reads as something else (a built-in name, or one with the prefix).
   */
  def spellings(memoryType: MemoryType): Seq[String] = memoryType match {
    case MemoryType.Custom(name) if !BuiltInNames.contains(name) && !name.startsWith(CustomPrefix) =>
      Seq(encode(memoryType), name)
    case other => Seq(encode(other))
  }
}
