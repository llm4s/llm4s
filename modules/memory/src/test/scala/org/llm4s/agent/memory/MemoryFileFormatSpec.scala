package org.llm4s.agent.memory

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, StandardCopyOption }
import java.time.Instant

/**
 * The files 1.0.0 wrote, opened by this version (#3039): the two files under `fixtures/memory-1.0.0` were written by the 1.0.0
 * `VectorMemoryStore` and `SQLiteMemoryStore` themselves (three memories, one with quotes and a backslash in its
 * metadata, one of a custom type; the vector file embedded with `MockEmbeddingService(3)`). Each is copied to a temporary
 * directory, opened with both stores, and must read back exactly what was written. Runs on Windows, as every suite of
 * `memory` does.
 */
class MemoryFileFormatSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val t0 = Instant.ofEpochMilli(1_700_000_000_123L)

  private val written = Seq(
    Memory(
      MemoryId("fact"),
      "Prefers Scala",
      MemoryType.UserFact,
      Map("user_id" -> "u1", "q" -> "say \"hi\" c:\\temp"),
      t0,
      Some(0.9)
    ),
    Memory(
      MemoryId("turn"),
      "User: hello",
      MemoryType.Conversation,
      Map("conversation_id" -> "c1"),
      t0.plusSeconds(1),
      None
    ),
    Memory(
      MemoryId("report"),
      "Quarterly report",
      MemoryType.Custom("Report"),
      Map("entity_id" -> "e1"),
      t0.plusSeconds(2),
      Some(0.5)
    )
  )

  private def fields(m: Memory) = (m.id, m.content, m.memoryType, m.metadata, m.timestamp, m.importance)

  private def copied[A](name: String)(body: String => A): A = {
    val dir  = Files.createTempDirectory("llm4s-format")
    val file = dir.resolve(name)
    Files.copy(getClass.getResourceAsStream(s"/fixtures/memory-1.0.0/$name"), file, StandardCopyOption.REPLACE_EXISTING)
    MemoryStoreContract
      .after { Files.list(dir).forEach((p: Path) => Files.delete(p)); Files.delete(dir) }(body(file.toString))
  }

  "A VectorMemoryStore file written by 1.0.0" should "be migrated on open and read back unchanged, embeddings included" in
    copied("vector-1.0.0.db") { path =>
      val service = MockEmbeddingService(dimensions = 3)
      val store   = VectorMemoryStore(path, service).value
      MemoryStoreContract.after(store.close()) {
        val read = store.recall(MemoryFilter.All, 10).value.sortBy(_.timestamp)
        read.map(fields) shouldBe written.map(fields)
        read.foreach(m => m.embedding.map(_.toSeq) shouldBe Some(service.embed(m.content).value.toSeq))
        store.recall(MemoryFilter.ByType(MemoryType.Custom("Report")), 10).value.map(_.id.value) shouldBe Seq("report")
        store.search("Prefers Scala", 1, MemoryFilter.All).value.map(_.memory.id.value) shouldBe Seq("fact")
        store.vectorStats.value.embeddingDimensions shouldBe Set(3)
      }
      // and then as a SQLiteMemoryStore, which is the same schema now
      val sqlite = SQLiteMemoryStore(path).value
      MemoryStoreContract.after(sqlite.close()) {
        sqlite.recall(MemoryFilter.All, 10).value.sortBy(_.timestamp).map(fields) shouldBe written.map(fields)
        sqlite.search("quarterly", 5, MemoryFilter.All).value.map(_.memory.id.value) shouldBe Seq("report")
      }
    }

  "A SQLiteMemoryStore file written by 1.0.0" should "open with either store and read back unchanged" in
    copied("sqlite-1.0.0.db") { path =>
      val sqlite = SQLiteMemoryStore(path).value
      MemoryStoreContract.after(sqlite.close()) {
        sqlite.recall(MemoryFilter.All, 10).value.sortBy(_.timestamp).map(fields) shouldBe written.map(fields)
      }
      val vector = VectorMemoryStore(path, MockEmbeddingService(dimensions = 3)).value
      MemoryStoreContract.after(vector.close()) {
        vector.recall(MemoryFilter.All, 10).value.sortBy(_.timestamp).map(fields) shouldBe written.map(fields)
        vector.get(MemoryId("fact")).value.flatMap(_.embedding).map(_.toSeq) shouldBe Some(Seq(1f, 0f, 0f))
      }
    }
}
