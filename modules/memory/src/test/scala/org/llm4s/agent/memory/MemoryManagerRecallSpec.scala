package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.DriverManager
import scala.util.Using

/**
 * What a manager records, `getRelevantContext` finds, on every store (#3030). A store with an embedding service must
 * embed what the manager stores - neither `MemoryManager` nor `SemanticMemoryMiddleware` embeds it - or similarity
 * search never returns it. `modules/it`'s `PostgresMemoryStoreSpec` checks the same of `PostgresMemoryStore`.
 */
class MemoryManagerRecallSpec extends AnyFlatSpec with Matchers {

  /** Every text embeds to the same vector, so only whether a memory has an embedding decides whether it is found. */
  private val sameVector: EmbeddingService = new EmbeddingService {
    override def embed(text: String): Result[Array[Float]]                 = Right(Array(1f, 0f, 0f))
    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = Right(texts.map(_ => Array(1f, 0f, 0f)))
    override def dimensions: Int                                           = 3
  }

  private def right[A](r: Result[A]): A = r.fold(e => fail(e.message), identity)

  /** `body`, then `release`, however `body` ends. */
  private def ensuring[A](release: => Unit)(body: => A): A =
    Using.resource(new AutoCloseable { override def close(): Unit = release })(_ => body)

  private def recalled(store: MemoryStore, query: String): String = {
    val manager = right(SimpleMemoryManager.withStore(store).recordUserFact("Prefers Scala", Some("user-1"), Some(0.9)))
    right(manager.getRelevantContext(query))
  }

  // Keyword stores find it by a word it contains
  "InMemoryStore" should "find a recorded fact by keyword" in {
    recalled(InMemoryStore.empty, "Scala") should include("Prefers Scala")
  }

  "SQLiteMemoryStore" should "find a recorded fact by keyword" in {
    val store = right(SQLiteMemoryStore.inMemory())
    ensuring(store.close())(recalled(store, "Scala") should include("Prefers Scala"))
  }

  // Embedding stores find it by similarity alone: the query shares no word with the memory
  "InMemoryStore.withEmbeddingService" should "find a recorded fact by similarity" in {
    recalled(InMemoryStore.withEmbeddingService(sameVector), "favourite language") should include("Prefers Scala")
  }

  "VectorMemoryStore" should "find a recorded fact by similarity" in {
    val store = right(VectorMemoryStore.inMemory(sameVector))
    ensuring(store.close())(recalled(store, "favourite language") should include("Prefers Scala"))
  }

  it should "write a custom type with its prefix and read the bare spelling an older version wrote (#3031)" in {
    val dir  = Files.createTempDirectory("llm4s-vector-types")
    val file = dir.resolve("vector.db")
    ensuring { Files.deleteIfExists(file); Files.deleteIfExists(dir): Unit } {
      val report = Memory(MemoryId("r"), "quarterly report", MemoryType.Custom("Report"))
      val task   = Memory(MemoryId("t"), "custom task", MemoryType.Custom("task"))
      val legacy = Memory(MemoryId("l"), "legacy report", MemoryType.Custom("Report"))
      val store  = right(VectorMemoryStore(file.toString, sameVector))
      ensuring(store.close())(right(store.storeAll(Seq(report, task, legacy))))

      Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$file")) { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery("SELECT id, memory_type FROM memories ORDER BY id")) { rs =>
            val rows = Iterator.continually(rs).takeWhile(_.next()).map(r => r.getString(1) -> r.getString(2)).toList
            rows shouldBe List("l" -> "custom:Report", "r" -> "custom:Report", "t" -> "custom:task")
          }
          // What a store before #3031 wrote for Custom("Report")
          stmt.executeUpdate("UPDATE memories SET memory_type = 'Report' WHERE id = 'l'")
        }
      }

      val reopened = right(VectorMemoryStore(file.toString, sameVector))
      ensuring(reopened.close()) {
        right(reopened.get(MemoryId("t"))).map(_.memoryType) shouldBe Some(MemoryType.Custom("task"))
        right(reopened.get(MemoryId("l"))).map(_.memoryType) shouldBe Some(MemoryType.Custom("Report"))
        right(reopened.recall(MemoryFilter.ByType(MemoryType.Custom("Report")))).map(_.id.value).toSet shouldBe
          Set("r", "l")
        right(reopened.count(MemoryFilter.ByType(MemoryType.Task))) shouldBe 0L
      }
    }
  }
}
