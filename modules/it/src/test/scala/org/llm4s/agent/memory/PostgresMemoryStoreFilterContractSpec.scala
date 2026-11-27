package org.llm4s.agent.memory

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Using

/**
 * `MemoryStoreFilterContractSpec` (`modules/memory`) against `PostgresMemoryStore` (#3031), on the same fixtures and
 * filters: what `MemoryFilter.matches` says about a memory is what the store answers for `recall`, `count`,
 * `deleteMatching` and `search`, with and without an embedding service. `Not` keeps rows without the key or the
 * importance, `HasMetadata`, `MetadataContains`, `ContentContains` and `Custom` are honoured, and a metadata key may
 * be any string. The SQL translation itself is `PostgresMemoryStoreFilterSqlSpec` (`modules/memory-postgres`).
 *
 * Tier 2 (`@Docker`), enabled as `PostgresMemoryStoreSpec` is: `POSTGRES_TEST_ENABLED=true`, with the connection from
 * `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD`. Each test loads its own
 * table and drops it afterwards.
 */
@Docker
class PostgresMemoryStoreFilterContractSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  import MemoryStoreFilterContractSpec.{ filters, fixtures }

  private def isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private val tables = new AtomicInteger()
  private val suffix = System.currentTimeMillis()

  private var pool: HikariDataSource = _

  private def dataSource(): HikariDataSource = {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    if (pool == null) {
      val config = new HikariConfig()
      val host   = sys.env.getOrElse("POSTGRES_HOST", "localhost")
      val port   = sys.env.getOrElse("POSTGRES_PORT", "5432")
      val db     = sys.env.getOrElse("POSTGRES_DB", "postgres")
      config.setJdbcUrl(s"jdbc:postgresql://$host:$port/$db")
      config.setUsername(sys.env.getOrElse("POSTGRES_USER", "postgres"))
      config.setPassword(sys.env.getOrElse("POSTGRES_PASSWORD", "password"))
      config.setMaximumPoolSize(4)
      pool = new HikariDataSource(config)
    }
    pool
  }

  override def afterAll(): Unit = if (pool != null) pool.close()

  private val subjects: Seq[(String, Option[EmbeddingService])] = Seq(
    "PostgresMemoryStore (keyword search)" -> None,
    "PostgresMemoryStore (vector search)"  -> Some(MockEmbeddingService(64))
  )

  /** Run `test` against a fresh table holding the fixtures; always drop the table. */
  private def withLoaded(service: Option[EmbeddingService])(test: MemoryStore => Any): Unit = {
    val ds    = dataSource()
    val table = s"filter_contract_${suffix}_${tables.incrementAndGet()}"
    Using.resource(new AutoCloseable { override def close(): Unit = drop(ds, table) }) { _ =>
      val empty  = right(PostgresMemoryStore.fromDataSource(ds, table, service))
      val loaded = right(empty.storeAll(fixtures))
      test(loaded)
    }
  }

  private def drop(ds: HikariDataSource, table: String): Unit =
    Using.resource(ds.getConnection)(c =>
      Using.resource(c.createStatement())(s =>
        Seq(table, s"${table}_applied", s"${table}_fences").foreach(t => s.execute(s"DROP TABLE IF EXISTS $t"))
      )
    )

  private def ids(memories: Seq[Memory]): Set[String] = memories.map(_.id.value).toSet

  private def right[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  for {
    (name, service)      <- subjects
    (filterName, filter) <- filters
  } {
    val expected = fixtures.filter(filter.matches)

    name should s"recall exactly the memories $filterName matches" in withLoaded(service) { store =>
      ids(right(store.recall(filter, 100))) shouldBe ids(expected)
    }

    it should s"count exactly the memories $filterName matches ($name)" in withLoaded(service) { store =>
      right(store.count(filter)) shouldBe expected.size.toLong
    }

    it should s"keep only the memories $filterName does not match after deleteMatching ($name)" in
      withLoaded(service) { store =>
        val after = right(store.deleteMatching(filter))
        ids(right(after.recall(MemoryFilter.All, 100))) shouldBe ids(fixtures.filterNot(filter.matches))
      }

    it should s"return only memories $filterName matches from search ($name)" in withLoaded(service) { store =>
      val found = right(store.search("note plan identifiers case", 100, filter)).map(_.memory)
      found.foreach(m => withClue(s"${m.id.value}: ")(filter.matches(m) shouldBe true))
      // Vector search ranks every embedded memory the filter accepts, so with topK above the count it finds them all
      if (service.isDefined) ids(found) shouldBe ids(expected)
    }
  }

  private lazy val leaves: Vector[MemoryFilter] =
    filters.map(_._2).filter(MemoryStoreFilterContractSpec.isLeaf).toVector

  for ((name, service) <- subjects) {
    name should "answer recall, count and deleteMatching like `matches` for 300 random filter trees" in
      withLoaded(service) { store =>
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

    it should s"delete exactly the rows `matches` accepts for random filter trees ($name)" in {
      val random = new scala.util.Random(20261007L)
      (1 to 40).foreach { n =>
        val tree = MemoryStoreFilterContractSpec.randomTree(leaves, random, 3)
        withLoaded(service) { store =>
          val after = right(store.deleteMatching(tree))
          withClue(s"tree #$n $tree: ")(
            ids(right(after.recall(MemoryFilter.All, 1000))) shouldBe ids(fixtures.filterNot(tree.matches))
          )
        }
      }
    }

    it should s"apply the limit after the filter, not before it ($name)" in withLoaded(service) { store =>
      val filter   = MemoryFilter.Custom(m => m.id.value.startsWith("pct") || m.id.value.startsWith("snake"))
      val expected = fixtures.filter(filter.matches).sortBy(m => -m.timestamp.toEpochMilli).take(2)
      right(store.recall(filter, 2)).map(_.id.value) shouldBe expected.map(_.id.value)
      right(store.recall(MemoryFilter.Custom(_.id.value.startsWith("pct")), 1)).map(_.id.value) shouldBe
        Seq("pct-1000")
    }
  }
}
