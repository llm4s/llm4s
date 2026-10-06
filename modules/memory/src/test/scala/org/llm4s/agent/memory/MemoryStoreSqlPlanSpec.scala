package org.llm4s.agent.memory

import org.llm4s.error.ConfigurationError
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, InvocationTargetException, Method, Proxy }
import java.nio.file.{ Files, Path }
import java.sql.{ Connection, DriverManager }
import java.util.Comparator
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * What the SQL-backed stores actually send to SQLite, and how vector search treats a store that holds vectors of
 * more than one dimension.
 */
class MemoryStoreSqlPlanSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = _

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("llm4s-memory-plan")

  override def afterEach(): Unit =
    Using.resource(Files.walk(tempDir))(paths =>
      paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
    )

  private def dbPath: String = tempDir.resolve("memories.db").toString

  private def memory(id: String, content: String, entity: Option[String] = None): Memory =
    Memory(
      id = MemoryId(id),
      content = content,
      memoryType = MemoryType.Task,
      metadata = entity.map("entity_id" -> _).toMap
    )

  /** An open SQLite store whose connection records the SQL it is asked to prepare and its transaction calls. */
  private def withRecording[A](f: (SQLiteMemoryStore, RecordingConnection) => A): A = {
    val recording = new RecordingConnection(DriverManager.getConnection(s"jdbc:sqlite:$dbPath"))
    val store = SQLiteMemoryStore
      .open(dbPath, MemoryStoreConfig.default, _ => recording.proxy)
      .fold(e => fail(e.message), identity)
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() })(_ => f(store, recording))
  }

  // ===== SQL narrows, even when a Custom predicate sits beside the SQL-expressible part =====

  "SQLiteMemoryStore" should "narrow by the entity column for And(ByEntity, Custom), not read every row" in
    withRecording { (store, recording) =>
      store.storeAll(
        Seq(memory("a", "alpha", Some("e1")), memory("b", "beta", Some("e2")), memory("c", "gamma"))
      ) shouldBe a[
        Right[_, _]
      ]
      recording.clear()

      val filter = MemoryFilter.And(MemoryFilter.ByEntity(EntityId("e1")), MemoryFilter.Custom(_.content == "alpha"))
      store.recall(filter, 10).map(_.map(_.id.value)) shouldBe Right(Seq("a"))

      val selects = recording.prepared.filter(_.startsWith("SELECT * FROM memories"))
      selects should have size 1
      selects.head should include("entity_id = ?")
    }

  it should "narrow search and count the same way" in withRecording { (store, recording) =>
    store.storeAll(Seq(memory("a", "alpha note", Some("e1")), memory("b", "beta note", Some("e2")))) shouldBe a[
      Right[_, _]
    ]
    recording.clear()

    val filter = MemoryFilter.And(MemoryFilter.ByEntity(EntityId("e1")), MemoryFilter.Custom(_ => true))
    store.search("note", 5, filter).map(_.map(_.memory.id.value)) shouldBe Right(Seq("a"))
    store.count(filter) shouldBe Right(1L)

    recording.prepared.filter(sql => sql.contains("FROM memories") && !sql.contains("INSERT")).foreach { sql =>
      withClue(s"$sql: ")(sql should include("entity_id = ?"))
    }
  }

  it should "still apply the limit after `matches`, not before it" in withRecording { (store, _) =>
    val rows = (1 to 6).map(i =>
      memory(s"m$i", s"row $i", Some("e1")).copy(timestamp = java.time.Instant.ofEpochSecond(i.toLong))
    )
    store.storeAll(rows) shouldBe a[Right[_, _]]

    val filter = MemoryFilter.And(MemoryFilter.ByEntity(EntityId("e1")), MemoryFilter.Custom(_.id.value.endsWith("2")))
    store.recall(filter, 1).map(_.map(_.id.value)) shouldBe Right(Seq("m2"))
  }

  // ===== Metadata comes back as it went in =====

  it should "return metadata holding backslash sequences exactly as it was stored" in withRecording { (store, _) =>
    val awkward = Map(
      "path"    -> """c:\temp\new""", // \t and \n are two characters each, not a tab and a newline
      "regex"   -> """\d+\.\d*""",
      "quote"   -> """say "hi" \ bye""",
      "control" -> "tab\there\nnewline\rreturn",
      "unicode" -> "caf\u00e9 \u65e5\u672c\u8a9e",
      "a\\b"    -> "key with a backslash"
    )
    store.store(memory("awkward", "x").copy(metadata = awkward)) shouldBe a[Right[_, _]]

    store.get(MemoryId("awkward")).map(_.map(_.metadata)) shouldBe Right(Some(awkward))
  }

  it should "still read a row whose stored metadata is not valid JSON, as the old encoder could write it" in {
    // The old encoder wrote a control character other than tab, newline and carriage return raw
    val bell = "\u0007"
    store(dbPath)(first => first.store(memory("legacy", "legacy row")) shouldBe a[Right[_, _]])
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { c =>
      Using.resource(c.prepareStatement("UPDATE memories SET metadata_json = ? WHERE id = 'legacy'")) { stmt =>
        stmt.setString(1, s"""{"note":"ring${bell}ing","tag":"plain"}""")
        stmt.executeUpdate()
      }
    }

    store(dbPath) { reopened =>
      reopened.get(MemoryId("legacy")).map(_.map(_.metadata)) shouldBe
        Right(Some(Map("note" -> s"ring${bell}ing", "tag" -> "plain")))
    }
  }

  private def store[A](path: String)(f: SQLiteMemoryStore => A): A = {
    val opened = SQLiteMemoryStore(path).fold(e => fail(e.message), identity)
    Using.resource(new AutoCloseable { override def close(): Unit = opened.close() })(_ => f(opened))
  }

  // ===== deleteMatching is one transaction, not one commit per row =====

  it should "delete the rows a Custom filter accepts in a single transaction" in withRecording { (store, recording) =>
    store.storeAll((1 to 20).map(i => memory(s"m$i", s"row $i"))) shouldBe a[Right[_, _]]
    recording.clear()

    val after = store.deleteMatching(MemoryFilter.Custom(_.id.value.length == 3))
    after shouldBe a[Right[_, _]]

    store.count() shouldBe Right(9L) // m10 to m20 are gone: eleven rows
    recording.calls.count(_ == "commit") shouldBe 1
    recording.calls.filter(_.startsWith("setAutoCommit")) shouldBe Seq("setAutoCommit(false)", "setAutoCommit(true)")
  }

  it should "leave every row in place, in both tables, and autocommit restored when a delete fails midway" in {
    val recording = new RecordingConnection(DriverManager.getConnection(s"jdbc:sqlite:$dbPath"))
    val store =
      SQLiteMemoryStore
        .open(dbPath, MemoryStoreConfig.default, _ => recording.proxy)
        .fold(e => fail(e.message), identity)
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
      store.storeAll((1 to 5).map(i => memory(s"m$i", s"row $i"))) shouldBe a[Right[_, _]]
      // The real engine aborts the fourth delete, whichever row it is: three rows are gone by then
      Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { other =>
        Using.resource(other.createStatement())(
          _.execute(
            "CREATE TRIGGER stop_midway BEFORE DELETE ON memories WHEN (SELECT COUNT(*) FROM memories) <= 2 " +
              "BEGIN SELECT RAISE(ABORT, 'stop midway'); END"
          )
        )
      }
      recording.clear()

      val result = store.deleteMatching(MemoryFilter.Custom(_ => true))

      result.isLeft shouldBe true
      store.count() shouldBe Right(5L)
      // The full-text index went with the transaction: nothing was deleted from it either
      store.search("row", 10).map(_.size) shouldBe Right(5)
      recording.calls.count(_ == "rollback") shouldBe 1
      recording.calls.filter(_.startsWith("setAutoCommit")).last shouldBe "setAutoCommit(true)"
    }
  }

  it should "register a java_lower function that lower-cases like String.toLowerCase and passes NULL through" in {
    Using.resource(DriverManager.getConnection("jdbc:sqlite::memory:")) { c =>
      FilterSupport.registerJavaLower(c)
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery("SELECT java_lower('\u00c9COLE'), java_lower('\u0130'), java_lower(NULL)")) {
          rs =>
            rs.next() shouldBe true
            rs.getString(1) shouldBe "\u00c9COLE".toLowerCase
            rs.getString(2) shouldBe "\u0130".toLowerCase
            rs.getString(3) shouldBe null
        }
      }
    }
  }

  "VectorMemoryStore.deleteMatching" should "roll back, leaving every row, when a delete fails midway" in {
    withVector(MockEmbeddingService(dimensions = 8)) { vector =>
      vector.storeAll((1 to 5).map(i => memory(s"m$i", s"row $i"))) shouldBe a[Right[_, _]]
      Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { other =>
        Using.resource(other.createStatement())(
          _.execute(
            "CREATE TRIGGER stop_midway BEFORE DELETE ON memories WHEN (SELECT COUNT(*) FROM memories) <= 2 " +
              "BEGIN SELECT RAISE(ABORT, 'stop midway'); END"
          )
        )
      }

      vector.deleteMatching(MemoryFilter.Custom(_ => true)).isLeft shouldBe true

      vector.count() shouldBe Right(5L)
    }
  }

  it should "delete the rows a Custom filter accepts, and no others" in {
    withVector(MockEmbeddingService(dimensions = 8)) { vector =>
      vector.storeAll((1 to 6).map(i => memory(s"m$i", s"row $i"))) shouldBe a[Right[_, _]]

      vector.deleteMatching(MemoryFilter.Custom(_.id.value.endsWith("3"))) shouldBe a[Right[_, _]]

      vector.recall(MemoryFilter.All, 10).map(_.map(_.id.value).toSet) shouldBe Right(Set("m1", "m2", "m4", "m5", "m6"))
    }
  }

  // ===== deleteMatching reads no rows for an exact filter, and no embeddings unless a Custom can look at them =====

  /** Rows in the main table and in the full-text index, counted on a separate connection. */
  private def tableCounts(): (Long, Long) =
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { c =>
      def count(table: String): Long =
        Using.resource(c.createStatement())(st =>
          Using.resource(st.executeQuery(s"SELECT COUNT(*) FROM $table")) { rs =>
            rs.next(); rs.getLong(1)
          }
        )
      (count("memories"), count("memories_fts"))
    }

  /** The SELECTs that read whole rows (or embeddings) from the memories table. */
  private def rowReads(recording: RecordingConnection): Seq[String] =
    recording.prepared.filter(sql => sql.startsWith("SELECT") && sql.contains("FROM memories"))

  private def withRecordingVector[A](f: (VectorMemoryStore, RecordingConnection) => A): A = {
    val recording = new RecordingConnection(DriverManager.getConnection(s"jdbc:sqlite:$dbPath"))
    val store = VectorMemoryStore
      .open(dbPath, MockEmbeddingService(dimensions = 8), MemoryStoreConfig.default, _ => recording.proxy)
      .fold(e => fail(e.message), identity)
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() })(_ => f(store, recording))
  }

  "SQLiteMemoryStore.deleteMatching" should "delete an exact filter's rows, and their index entries, in SQL alone" in
    withRecording { (store, recording) =>
      val rows = (1 to 6).map(i => memory(s"m$i", s"row $i", Some(if (i % 2 == 0) "even" else "odd")))
      store.storeAll(rows.map(_.withEmbedding(Array.fill(8)(0.5f)))) shouldBe a[Right[_, _]]
      recording.clear()

      store.deleteMatching(MemoryFilter.ByEntity(EntityId("even"))) shouldBe a[Right[_, _]]

      rowReads(recording) shouldBe empty
      recording.prepared should contain("DELETE FROM memories WHERE COALESCE(entity_id = ?, 0)")
      recording.calls.count(_ == "commit") shouldBe 1
      tableCounts() shouldBe ((3L, 3L))
      store.recall(MemoryFilter.All, 10).map(_.map(_.id.value).toSet) shouldBe Right(Set("m1", "m3", "m5"))
      store.search("row", 10).map(_.map(_.memory.id.value).toSet) shouldBe Right(Set("m1", "m3", "m5"))
    }

  it should "clear both tables for All without reading a row" in withRecording { (store, recording) =>
    store.storeAll((1 to 4).map(i => memory(s"m$i", s"row $i"))) shouldBe a[Right[_, _]]
    recording.clear()

    store.deleteMatching(MemoryFilter.All) shouldBe a[Right[_, _]]

    rowReads(recording) shouldBe empty
    tableCounts() shouldBe ((0L, 0L))
  }

  it should "leave the embedding column out when `matches` decides a filter that cannot read it" in
    withRecording { (store, recording) =>
      store.storeAll(Seq(memory("a", "alpha"), memory("b", "beta").copy(metadata = Map("" -> "x")))) shouldBe
        a[Right[_, _]]
      recording.clear()

      // An empty metadata key has no exact JSON path, so `matches` decides it
      store.deleteMatching(MemoryFilter.HasMetadata("")) shouldBe a[Right[_, _]]
      store.count(MemoryFilter.HasMetadata("")) shouldBe Right(0L)

      val reads = rowReads(recording)
      reads should have size 2
      reads.foreach(sql => withClue(sql)(sql should (include("NULL AS embedding_blob").and(not.include("SELECT *")))))
      tableCounts() shouldBe ((1L, 1L))
      store.recall(MemoryFilter.All, 10).map(_.map(_.id.value)) shouldBe Right(Seq("a"))
    }

  it should "give a Custom predicate the embedding, and delete exactly the rows it accepts" in
    withRecording { (store, recording) =>
      store.storeAll(
        Seq(memory("with", "has a vector").withEmbedding(Array.fill(8)(0.1f)), memory("without", "no vector"))
      ) shouldBe a[Right[_, _]]
      recording.clear()

      store.deleteMatching(MemoryFilter.Custom(_.embedding.exists(_.length == 8))) shouldBe a[Right[_, _]]

      store.recall(MemoryFilter.All, 10).map(_.map(_.id.value)) shouldBe Right(Seq("without"))
      tableCounts() shouldBe ((1L, 1L))
      rowReads(recording).head should (include("embedding_blob").and(not.include("NULL AS")))
    }

  "VectorMemoryStore.deleteMatching" should "delete an exact filter's rows, and their index entries, in SQL alone" in
    withRecordingVector { (vector, recording) =>
      val rows = (1 to 6).map(i => memory(s"m$i", s"row $i", Some(if (i % 2 == 0) "even" else "odd")))
      vector.storeAll(rows) shouldBe a[Right[_, _]]
      recording.clear()

      vector.deleteMatching(MemoryFilter.ByEntity(EntityId("even"))) shouldBe a[Right[_, _]]

      rowReads(recording) shouldBe empty
      recording.prepared.exists(
        _.startsWith("DELETE FROM memories_fts WHERE id IN (SELECT id FROM memories WHERE")
      ) shouldBe
        true
      recording.calls.count(_ == "commit") shouldBe 1
      tableCounts() shouldBe ((3L, 3L))
      vector.recall(MemoryFilter.All, 10).map(_.map(_.id.value).toSet) shouldBe Right(Set("m1", "m3", "m5"))
    }

  it should "clear both tables for All without reading a row" in withRecordingVector { (vector, recording) =>
    vector.storeAll((1 to 4).map(i => memory(s"m$i", s"row $i"))) shouldBe a[Right[_, _]]
    recording.clear()

    vector.deleteMatching(MemoryFilter.All) shouldBe a[Right[_, _]]

    rowReads(recording) shouldBe empty
    tableCounts() shouldBe ((0L, 0L))
  }

  it should "leave the embedding column out when `matches` decides a filter that cannot read it" in
    withRecordingVector { (vector, recording) =>
      vector.storeAll(
        Seq(
          memory("a", "alpha").copy(metadata = Map("tag" -> "keep")),
          memory("b", "beta").copy(metadata = Map("tag" -> "drop me"))
        )
      ) shouldBe a[Right[_, _]]
      recording.clear()

      // The file store has no exact SQL for MetadataContains, so `matches` decides it
      vector.deleteMatching(MemoryFilter.MetadataContains("tag", "drop")) shouldBe a[Right[_, _]]
      vector.count(MemoryFilter.MetadataContains("tag", "drop")) shouldBe Right(0L)

      val reads = rowReads(recording)
      reads should have size 2
      reads.foreach(sql => withClue(sql)(sql should (include("NULL AS embedding").and(not.include("SELECT *")))))
      tableCounts() shouldBe ((1L, 1L))
      vector.recall(MemoryFilter.All, 10).map(_.map(_.id.value)) shouldBe Right(Seq("a"))
    }

  it should "give a Custom predicate the embedding, and delete exactly the rows it accepts" in
    withRecordingVector { (vector, recording) =>
      vector.storeAll(
        Seq(
          memory("eight", "x").withEmbedding(Array.fill(8)(0.1f)),
          memory("four", "y").withEmbedding(Array.fill(4)(0.1f))
        )
      ) shouldBe a[Right[_, _]]
      recording.clear()

      vector.deleteMatching(MemoryFilter.Custom(_.embedding.exists(_.length == 4))) shouldBe a[Right[_, _]]

      rowReads(recording).head should (include("embedding").and(not.include("NULL AS")))
      tableCounts() shouldBe ((1L, 1L))
      vector.recall(MemoryFilter.All, 10).map(_.map(_.id.value)) shouldBe Right(Seq("eight"))
    }

  // ===== A store holding vectors of more than one dimension stays searchable =====

  private def withVector[A](service: EmbeddingService)(f: VectorMemoryStore => A): A = {
    val store = VectorMemoryStore(dbPath, service).fold(e => fail(e.message), identity)
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() })(_ => f(store))
  }

  "VectorMemoryStore.search" should "search the vectors that match the query's dimension and skip the others" in {
    withVector(MockEmbeddingService(dimensions = 32)) { old =>
      old.store(memory("old-1", "Scala is a language")) shouldBe a[Right[_, _]]
      old.store(memory("old-2", "Scala runs on the JVM")) shouldBe a[Right[_, _]]
    }
    withVector(MockEmbeddingService(dimensions = 16)) { current =>
      current.store(memory("new-1", "Scala has traits")) shouldBe a[Right[_, _]]
      current.store(memory("new-2", "Scala has givens")) shouldBe a[Right[_, _]]

      val found = current.search("Scala", topK = 10).fold(e => fail(e.message), identity)

      found.map(_.memory.id.value).toSet shouldBe Set("new-1", "new-2")
    }
  }

  it should "report a mismatch, naming both dimensions, when no stored vector has the query's dimension" in {
    withVector(MockEmbeddingService(dimensions = 32))(_.store(memory("a", "Scala")))
    withVector(MockEmbeddingService(dimensions = 16))(_.store(memory("b", "Scala")))

    withVector(MockEmbeddingService(dimensions = 8)) { other =>
      val result = other.search("Scala", topK = 3)

      result.isLeft shouldBe true
      result.left.toOption.get shouldBe a[ConfigurationError]
      result.left.toOption.get.message should (include("8").and(include("16").or(include("32"))))
    }
  }

  it should "still search normally when every stored vector agrees with the query" in {
    withVector(MockEmbeddingService(dimensions = 16)) { s =>
      s.store(memory("a", "Scala")) shouldBe a[Right[_, _]]
      s.search("Scala", topK = 3).toOption.get.map(_.memory.id.value) shouldBe Seq("a")
    }
  }
}

/** A real connection that records the statements prepared on it, and its transaction calls. */
final private class RecordingConnection(real: Connection) {
  private val log  = new ConcurrentLinkedQueue[String]()
  private val prep = new ConcurrentLinkedQueue[String]()

  def calls: Seq[String]    = log.asScala.toSeq
  def prepared: Seq[String] = prep.asScala.toSeq
  def clear(): Unit         = { log.clear(); prep.clear() }

  val proxy: Connection = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[Connection]),
      new InvocationHandler {
        override def invoke(target: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          val arguments = Option(args).getOrElse(Array.empty[AnyRef])
          method.getName match {
            case "prepareStatement" =>
              val sql = arguments.head.toString
              prep.add(sql)
              log.add("prepareStatement")
            case "setAutoCommit"                          => log.add(s"setAutoCommit(${arguments.head})")
            case name @ ("commit" | "rollback" | "close") => log.add(name)
            case _                                        => ()
          }
          try method.invoke(real, arguments: _*)
          catch { case e: InvocationTargetException => throw e.getCause }
        }
      }
    )
    .asInstanceOf[Connection]
}
