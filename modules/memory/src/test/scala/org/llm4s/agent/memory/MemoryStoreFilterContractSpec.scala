package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.Comparator
import scala.util.Using

/**
 * One contract, every store: what [[MemoryFilter.matches]] says about a memory in memory is what a store must
 * answer for `recall`, `count`, `deleteMatching` and `search`.
 *
 * The SQL-backed stores translate filters to SQL, and a filter they cannot translate (`Custom`) or translate
 * loosely (`LIKE` patterns where `%` and `_` are wildcards, and where `caseSensitive` is not honoured) used to
 * answer differently from `InMemoryStore`. A store that ignores a filter silently returns the wrong rows, and
 * `deleteMatching` deletes them, so every filter below runs against every store.
 */
class MemoryStoreFilterContractSpec extends AnyFlatSpec with Matchers {

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
  private val fixtures: Seq[Memory] = Seq(
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

  private val filters: Seq[(String, MemoryFilter)] = Seq(
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

  // ===== The stores under contract =====

  private val embeddings = MockEmbeddingService.default

  private def deleteTree(dir: Path): Unit =
    Using.resource(Files.walk(dir))(paths =>
      paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
    )

  /** A way to build an empty store, and to dispose of it again. */
  final private case class Subject(name: String, create: Path => Result[MemoryStore])

  private val subjects: Seq[Subject] = Seq(
    Subject("InMemoryStore", _ => Right(InMemoryStore.empty)),
    Subject("SQLiteMemoryStore (in memory)", _ => SQLiteMemoryStore.inMemory()),
    Subject("SQLiteMemoryStore (file)", dir => SQLiteMemoryStore(dir.resolve("sqlite.db").toString)),
    Subject("VectorMemoryStore (in memory)", _ => VectorMemoryStore.inMemory()),
    Subject("VectorMemoryStore (file)", dir => VectorMemoryStore(dir.resolve("vector.db").toString, embeddings))
  )

  /** Run `test` against a store holding the fixtures; always close the store and delete its files. */
  private def withLoaded(subject: Subject)(test: MemoryStore => Any): Unit = {
    val dir = Files.createTempDirectory("llm4s-filter-contract")
    Using.resource(new AutoCloseable { override def close(): Unit = deleteTree(dir) }) { _ =>
      val empty  = subject.create(dir).fold(e => fail(s"cannot create ${subject.name}: ${e.message}"), identity)
      val loaded = empty.storeAll(fixtures).fold(e => fail(s"cannot load ${subject.name}: ${e.message}"), identity)
      Using.resource(new AutoCloseable { override def close(): Unit = MemoryStoreFilterContractSpec.close(loaded) }) {
        _ => test(loaded)
      }
    }
  }

  private def ids(memories: Seq[Memory]): Set[String] = memories.map(_.id.value).toSet

  private def right[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  // ===== The contract =====

  for {
    subject              <- subjects
    (filterName, filter) <- filters
  } {
    val expected = fixtures.filter(filter.matches)

    subject.name should s"recall exactly the memories $filterName matches" in withLoaded(subject) { store =>
      ids(right(store.recall(filter, 100))) shouldBe ids(expected)
    }

    it should s"count exactly the memories $filterName matches (${subject.name})" in withLoaded(subject) { store =>
      right(store.count(filter)) shouldBe expected.size.toLong
    }

    it should s"keep only the memories $filterName does not match after deleteMatching (${subject.name})" in
      withLoaded(subject) { store =>
        val after = right(store.deleteMatching(filter))
        ids(right(after.recall(MemoryFilter.All, 100))) shouldBe ids(fixtures.filterNot(filter.matches))
      }

    it should s"return only memories $filterName matches from search (${subject.name})" in withLoaded(subject) {
      store =>
        val found = right(store.search("note plan identifiers case", 100, filter)).map(_.memory)
        found.foreach(m => withClue(s"${m.id.value}: ")(filter.matches(m) shouldBe true))
    }
  }

  // ===== Narrowing is sound: no filter tree makes a store drop a row `matches` accepts =====

  /** Leaves for the random trees: SQL-expressible, awkward (case folding, NULLs, JSON-path keys) and `Custom`. */
  private lazy val leaves: Vector[MemoryFilter] =
    filters.map(_._2).filter(MemoryStoreFilterContractSpec.isLeaf).toVector

  for (subject <- subjects)
    subject.name should "answer recall, count and deleteMatching like `matches` for 300 random filter trees" in
      withLoaded(subject) { store =>
        val random = new scala.util.Random(20261006L)
        (1 to 300).foreach { n =>
          val tree     = MemoryStoreFilterContractSpec.randomTree(leaves, random, 3)
          val expected = ids(fixtures.filter(tree.matches))
          withClue(s"tree #$n $tree: ") {
            ids(right(store.recall(tree, 1000))) shouldBe expected
            right(store.count(tree)) shouldBe expected.size.toLong
          }
        }
      }

  for (subject <- subjects)
    subject.name should "delete exactly the rows `matches` accepts for random filter trees" in {
      val random = new scala.util.Random(20261007L)
      (1 to 40).foreach { n =>
        val tree = MemoryStoreFilterContractSpec.randomTree(leaves, random, 3)
        withLoaded(subject) { store =>
          val after = right(store.deleteMatching(tree))
          withClue(s"tree #$n $tree: ")(
            ids(right(after.recall(MemoryFilter.All, 1000))) shouldBe ids(fixtures.filterNot(tree.matches))
          )
        }
      }
    }

  // ===== Limits apply after the filter, not before it =====

  for (subject <- subjects) {
    subject.name should "return the most recent matching memories when the limit is smaller than the matches" in
      withLoaded(subject) { store =>
        val filter   = MemoryFilter.Custom(m => m.id.value.startsWith("pct") || m.id.value.startsWith("snake"))
        val expected = fixtures.filter(filter.matches).sortBy(m => -m.timestamp.toEpochMilli).take(2)

        right(store.recall(filter, 2)).map(_.id.value) shouldBe expected.map(_.id.value)
      }

    it should s"keep the filter a hard limit, not a hint, when recall is limited (${subject.name})" in
      withLoaded(subject) { store =>
        // The two most recent memories overall are "back" and "plain": a limit applied before the filter would
        // return them and then drop them, so nothing would come back.
        val filter = MemoryFilter.Custom(m => m.id.value.startsWith("pct"))

        right(store.recall(filter, 1)).map(_.id.value) shouldBe Seq("pct-1000")
      }
  }
}

object MemoryStoreFilterContractSpec {

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
