package org.llm4s.agent.memory

import java.time.Instant

/**
 * The memories and filters of the filter contract: `MemoryStoreFilterContractSpec` runs them against this module's
 * stores, and `modules/it` against `PostgresMemoryStore`. It uses only main classes and the JDK, because `modules/it`
 * compiles this one file into its own tests (`Test / unmanagedSources`) rather than depend on this module's tests and,
 * through them, core's (#3217).
 */
object MemoryStoreFilterFixtures {

  private val base = Instant.ofEpochMilli(1700000000000L)

  private def memory(
    id: String,
    content: String,
    memoryType: MemoryType,
    metadata: Map[String, String],
    minute: Int
  ): Memory =
    Memory(
      id = MemoryId(id),
      content = content,
      memoryType = memoryType,
      metadata = metadata,
      timestamp = base.plusSeconds(60L * minute),
      importance = Some(0.5)
    )

  /** The rows are chosen so that a `%` or `_` read as a wildcard matches a row it should not. */
  val fixtures: Seq[Memory] = Seq(
    memory(
      "pct-100",
      "100% sure about the plan",
      MemoryType.Conversation,
      Map("tag" -> "50%_off", "src" -> "web"),
      1
    ),
    memory(
      "pct-1000",
      "1000 sure about the plan",
      MemoryType.Conversation,
      Map("tag" -> "5000xoff", "src" -> "web"),
      2
    ),
    memory("snake", "snake_case identifiers", MemoryType.Knowledge, Map("tag" -> "a_b"), 3),
    memory("snakeX", "snakeXcase identifiers", MemoryType.Knowledge, Map("tag" -> "aXb"), 4),
    memory("upper", "UPPER and lower case", MemoryType.Task, Map("note" -> "Mixed"), 5),
    memory("quote", "a note with quotes", MemoryType.UserFact, Map("quote" -> """say "hi" \ bye"""), 6),
    memory("plain", "plain note", MemoryType.UserFact, Map.empty, 7),
    memory("back", "path c:\\temp and 100%_done", MemoryType.Conversation, Map("tag" -> """c:\temp"""), 8),
    // Case folding: `matches` lower-cases with Java, SQL `lower()` folds ASCII only, so these rows tell them apart.
    memory("ecole-upper", "L\u00c9COLE du soir", MemoryType.Knowledge, Map("lang" -> "fr"), 9),
    memory("ecole-lower", "l\u00e9cole du matin", MemoryType.Knowledge, Map("lang" -> "FR"), 10),
    memory("istanbul", "\u0130stanbul calling", MemoryType.Knowledge, Map("lang" -> "tr"), 11),
    memory("kelvin", "\u212aELVIN scale", MemoryType.Knowledge, Map("lang" -> "en"), 12),
    memory("strasse", "Stra\u00dfe und STRASSE", MemoryType.Knowledge, Map("lang" -> "de"), 13),
    memory("decomposed", "e\u0301cole decomposed", MemoryType.Knowledge, Map.empty, 14),
    // Null columns: no importance, no entity, no conversation. `Not(...)` must still accept this row.
    Memory(
      id = MemoryId("bare"),
      content = "bare row",
      memoryType = MemoryType.Task,
      metadata = Map.empty,
      timestamp = base.plusSeconds(60L * 15),
      importance = None
    ),
    Memory(
      id = MemoryId("keyed"),
      content = "keyed row",
      memoryType = MemoryType.Task,
      metadata =
        Map("entity_id" -> "e1", "conversation_id" -> "c1", "a.b" -> "dotted", "a" -> "plain", "x[0]" -> "idx"),
      timestamp = base.plusSeconds(60L * 16),
      importance = Some(0.9)
    )
  )

  val filters: Seq[(String, MemoryFilter)] = Seq(
    "Custom"                         -> MemoryFilter.Custom(_.content.startsWith("snake")),
    "Custom on the id"               -> MemoryFilter.Custom(_.id.value.startsWith("pct")),
    "Custom that matches nothing"    -> MemoryFilter.Custom(_ => false),
    "Custom that matches everything" -> MemoryFilter.Custom(_ => true),
    "And(Custom, ByType)" -> MemoryFilter.And(
      MemoryFilter.Custom(_.id.value != "snake"),
      MemoryFilter.ByType(MemoryType.Knowledge)
    ),
    "Or(Custom, ByMetadata)" -> MemoryFilter.Or(
      MemoryFilter.Custom(_.content.contains("plain")),
      MemoryFilter.ByMetadata("src", "web")
    ),
    "Not(Custom)"                                 -> MemoryFilter.Not(MemoryFilter.Custom(_.content.contains("plan"))),
    "ContentContains with a percent sign"         -> MemoryFilter.ContentContains("100%"),
    "ContentContains with an underscore"          -> MemoryFilter.ContentContains("snake_case"),
    "ContentContains with a backslash"            -> MemoryFilter.ContentContains("c:\\temp"),
    "ContentContains, case insensitive"           -> MemoryFilter.ContentContains("upper"),
    "ContentContains, case sensitive"             -> MemoryFilter.ContentContains("UPPER", caseSensitive = true),
    "ContentContains, case sensitive, wrong case" -> MemoryFilter.ContentContains("upper", caseSensitive = true),
    "MetadataContains with a percent sign"        -> MemoryFilter.MetadataContains("tag", "50%"),
    "MetadataContains with an underscore"         -> MemoryFilter.MetadataContains("tag", "a_b"),
    "ByMetadata with a percent sign and an underscore" -> MemoryFilter.ByMetadata("tag", "50%_off"),
    "ByMetadata with quotes and a backslash"           -> MemoryFilter.ByMetadata("quote", """say "hi" \ bye"""),
    "ByMetadata with a backslash"                      -> MemoryFilter.ByMetadata("tag", """c:\temp"""),
    "HasMetadata"                                      -> MemoryFilter.HasMetadata("tag"),
    "ByType"                                           -> MemoryFilter.ByType(MemoryType.Conversation),
    "All"                                              -> MemoryFilter.All,
    // Case folding must mean what String.toLowerCase means, for any text
    "ContentContains of an accented capital" -> MemoryFilter.ContentContains("\u00e9cole"),
    "ContentContains of an accented small"   -> MemoryFilter.ContentContains("\u00c9COLE"),
    "ContentContains of a bare i"            -> MemoryFilter.ContentContains("i"),
    "ContentContains of a bare k"            -> MemoryFilter.ContentContains("k"),
    "ContentContains of ss"                  -> MemoryFilter.ContentContains("STRASSE"),
    "ContentContains of an eszett"           -> MemoryFilter.ContentContains("stra\u00dfe"),
    "ContentContains of a composed accent"   -> MemoryFilter.ContentContains("\u00e9cole"),
    "ContentContains of a decomposed accent" -> MemoryFilter.ContentContains("e\u0301cole"),
    "ContentContains of the empty string"    -> MemoryFilter.ContentContains(""),
    // Three-valued logic: a NULL column must not make `Not` drop a row `matches` accepts
    "Not(MinImportance)"    -> MemoryFilter.Not(MemoryFilter.MinImportance(0.4)),
    "Not(ByEntity)"         -> MemoryFilter.Not(MemoryFilter.ByEntity(EntityId("e1"))),
    "Not(ByConversation)"   -> MemoryFilter.Not(MemoryFilter.ByConversation("c1")),
    "Not(ByMetadata)"       -> MemoryFilter.Not(MemoryFilter.ByMetadata("lang", "fr")),
    "Not(HasMetadata)"      -> MemoryFilter.Not(MemoryFilter.HasMetadata("lang")),
    "Not(MetadataContains)" -> MemoryFilter.Not(MemoryFilter.MetadataContains("lang", "r")),
    "Not(ContentContains)"  -> MemoryFilter.Not(MemoryFilter.ContentContains("bare")),
    "Not(All)"              -> MemoryFilter.Not(MemoryFilter.All),
    "Not(None)"             -> MemoryFilter.Not(MemoryFilter.None),
    "Or(MinImportance, Not(MinImportance))" -> MemoryFilter.Or(
      MemoryFilter.MinImportance(0.4),
      MemoryFilter.Not(MemoryFilter.MinImportance(0.4))
    ),
    // `And(e, c)` is a subset of `e`, so `Not` of it is NOT `Not(e)`: narrowing through it would drop rows
    "Not(And(ByEntity, Custom))" -> MemoryFilter.Not(
      MemoryFilter.And(MemoryFilter.ByEntity(EntityId("e1")), MemoryFilter.Custom(_.content.contains("nothing")))
    ),
    "Not(Or(ByType, Custom))" -> MemoryFilter.Not(
      MemoryFilter.Or(MemoryFilter.ByType(MemoryType.Task), MemoryFilter.Custom(_.content.contains("keyed")))
    ),
    "And(ByEntity, Custom)" -> MemoryFilter.And(
      MemoryFilter.ByEntity(EntityId("e1")),
      MemoryFilter.Custom(_.content.contains("keyed"))
    ),
    // Every form of a type and a time range
    "ByTypes"                    -> MemoryFilter.ByTypes(Set(MemoryType.Task, MemoryType.Knowledge)),
    "ByTypes of one custom type" -> MemoryFilter.ByTypes(Set(MemoryType.Custom("nothing"))),
    "ByTypes of none"            -> MemoryFilter.ByTypes(Set.empty),
    "ByTimeRange after"          -> MemoryFilter.ByTimeRange(Some(base.plusSeconds(60L * 9)), None),
    "ByTimeRange before"         -> MemoryFilter.ByTimeRange(None, Some(base.plusSeconds(60L * 4))),
    "ByTimeRange between" -> MemoryFilter.ByTimeRange(Some(base.plusSeconds(60L * 3)), Some(base.plusSeconds(60L * 8))),
    "ByTimeRange unbounded" -> MemoryFilter.ByTimeRange(None, None),
    // Metadata keys that mean something in a JSON path
    "ByMetadata with a dotted key"        -> MemoryFilter.ByMetadata("a.b", "dotted"),
    "HasMetadata with a dotted key"       -> MemoryFilter.HasMetadata("a.b"),
    "MetadataContains with a dotted key"  -> MemoryFilter.MetadataContains("a.b", "dot"),
    "ByMetadata with a bracket key"       -> MemoryFilter.ByMetadata("x[0]", "idx"),
    "ByMetadata with a quote in the key"  -> MemoryFilter.ByMetadata("say \"hi\"", "x"),
    "ByMetadata is case sensitive"        -> MemoryFilter.ByMetadata("lang", "fr"),
    "ByMetadata is case sensitive, upper" -> MemoryFilter.ByMetadata("lang", "FR")
  )

  def isLeaf(filter: MemoryFilter): Boolean = filter match {
    case _: MemoryFilter.And | _: MemoryFilter.Or | _: MemoryFilter.Not => false
    case _                                                              => true
  }

  /** A random filter tree of at most `depth` levels over `leaves`, And / Or / Not above them. */
  def randomTree(leaves: Vector[MemoryFilter], random: scala.util.Random, depth: Int): MemoryFilter =
    if (depth == 0 || random.nextInt(4) == 0) leaves(random.nextInt(leaves.size))
    else
      random.nextInt(3) match {
        case 0 => MemoryFilter.And(randomTree(leaves, random, depth - 1), randomTree(leaves, random, depth - 1))
        case 1 => MemoryFilter.Or(randomTree(leaves, random, depth - 1), randomTree(leaves, random, depth - 1))
        case _ => MemoryFilter.Not(randomTree(leaves, random, depth - 1))
      }

  /** Release a store's connection, if it holds one. */
  def close(store: MemoryStore): Unit = store match {
    case s: SQLiteMemoryStore => s.close()
    case s: VectorMemoryStore => s.close()
    case _                    => ()
  }
}
