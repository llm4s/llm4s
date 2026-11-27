package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
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
class MemoryStoreFilterContractSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  import MemoryStoreFilterFixtures.{ filters, fixtures }

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

  // One directory for the suite: each store gets its own subdirectory, and the tree goes in `afterAll`.
  private val root    = Files.createTempDirectory("llm4s-filter-contract")
  private val counter = new AtomicInteger()

  private def load(subject: Subject): MemoryStore = {
    val dir   = Files.createDirectory(root.resolve(s"store-${counter.incrementAndGet()}"))
    val empty = subject.create(dir).fold(e => fail(s"cannot create ${subject.name}: ${e.message}"), identity)
    empty.storeAll(fixtures).fold(e => fail(s"cannot load ${subject.name}: ${e.message}"), identity)
  }

  // A read-only case (recall, count, search) runs against one loaded store per subject, built on first use. A
  // temporary directory and a SQLite file per case made this suite twenty times slower on Windows (#3216).
  private val shared = TrieMap.empty[String, MemoryStore]

  /** Run a read-only `test` against the subject's shared store holding the fixtures. */
  private def withShared(subject: Subject)(test: MemoryStore => Any): Unit =
    test(shared.getOrElseUpdate(subject.name, load(subject)))

  /** Run a `test` that writes against a fresh store holding the fixtures; always close the store. */
  private def withFresh(subject: Subject)(test: MemoryStore => Any): Unit = {
    val loaded = load(subject)
    Using.resource(new AutoCloseable { override def close(): Unit = MemoryStoreFilterFixtures.close(loaded) }) { _ =>
      test(loaded)
    }
  }

  override def afterAll(): Unit =
    Using.resource(new AutoCloseable { override def close(): Unit = deleteTree(root) })(_ =>
      shared.values.foreach(MemoryStoreFilterFixtures.close)
    )

  private def ids(memories: Seq[Memory]): Set[String] = memories.map(_.id.value).toSet

  private def right[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  // ===== The contract =====

  for {
    subject              <- subjects
    (filterName, filter) <- filters
  } {
    val expected = fixtures.filter(filter.matches)

    subject.name should s"recall exactly the memories $filterName matches" in withShared(subject) { store =>
      ids(right(store.recall(filter, 100))) shouldBe ids(expected)
    }

    it should s"count exactly the memories $filterName matches (${subject.name})" in withShared(subject) { store =>
      right(store.count(filter)) shouldBe expected.size.toLong
    }

    it should s"keep only the memories $filterName does not match after deleteMatching (${subject.name})" in
      withFresh(subject) { store =>
        val after = right(store.deleteMatching(filter))
        ids(right(after.recall(MemoryFilter.All, 100))) shouldBe ids(fixtures.filterNot(filter.matches))
      }

    it should s"return only memories $filterName matches from search (${subject.name})" in withShared(subject) {
      store =>
        val found = right(store.search("note plan identifiers case", 100, filter)).map(_.memory)
        found.foreach(m => withClue(s"${m.id.value}: ")(filter.matches(m) shouldBe true))
    }
  }

  // ===== Narrowing is sound: no filter tree makes a store drop a row `matches` accepts =====

  /** Leaves for the random trees: SQL-expressible, awkward (case folding, NULLs, JSON-path keys) and `Custom`. */
  private lazy val leaves: Vector[MemoryFilter] =
    filters.map(_._2).filter(MemoryStoreFilterFixtures.isLeaf).toVector

  for (subject <- subjects)
    subject.name should "answer recall, count and deleteMatching like `matches` for 300 random filter trees" in
      withShared(subject) { store =>
        val random = new scala.util.Random(20261006L)
        (1 to 300).foreach { n =>
          val tree     = MemoryStoreFilterFixtures.randomTree(leaves, random, 3)
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
        val tree = MemoryStoreFilterFixtures.randomTree(leaves, random, 3)
        withFresh(subject) { store =>
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
      withShared(subject) { store =>
        val filter   = MemoryFilter.Custom(m => m.id.value.startsWith("pct") || m.id.value.startsWith("snake"))
        val expected = fixtures.filter(filter.matches).sortBy(m => -m.timestamp.toEpochMilli).take(2)

        right(store.recall(filter, 2)).map(_.id.value) shouldBe expected.map(_.id.value)
      }

    it should s"keep the filter a hard limit, not a hint, when recall is limited (${subject.name})" in
      withShared(subject) { store =>
        // The two most recent memories overall are "back" and "plain": a limit applied before the filter would
        // return them and then drop them, so nothing would come back.
        val filter = MemoryFilter.Custom(m => m.id.value.startsWith("pct"))

        right(store.recall(filter, 1)).map(_.id.value) shouldBe Seq("pct-1000")
      }
  }
}
