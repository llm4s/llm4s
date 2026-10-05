package org.llm4s.agent.memory

import org.llm4s.error.ConfigurationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.lang.reflect.{ InvocationHandler, InvocationTargetException, Method, Proxy }
import java.nio.file.{ Files, Path }
import java.sql.{ Connection, DriverManager }
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Using

/** Open failures, embedding dimension mismatches and schema versions of the SQL-backed stores. */
class MemoryStoreHardeningSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val first = Memory(id = MemoryId("m1"), content = "The user prefers Scala", memoryType = MemoryType.UserFact)

  private var tempDir: Path = _

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("llm4s-memory-hardening")

  override def afterEach(): Unit =
    Using.resource(Files.walk(tempDir))(paths =>
      paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
    )

  private def dbPath: String = tempDir.resolve("memories.db").toString

  "SQLiteMemoryStore.open" should "close the connection when schema setup fails" in {
    val counting = new CountingConnection(DriverManager.getConnection(s"jdbc:sqlite:$dbPath"), failStatements = true)

    val result = SQLiteMemoryStore.open(dbPath, MemoryStoreConfig.default, _ => counting.proxy)

    result.isLeft shouldBe true
    counting.closes.get shouldBe 1
  }

  it should "not close the connection of a store it opened" in {
    val counting = new CountingConnection(DriverManager.getConnection(s"jdbc:sqlite:$dbPath"), failStatements = false)

    val store = SQLiteMemoryStore.open(dbPath, MemoryStoreConfig.default, _ => counting.proxy).toOption.get
    counting.closes.get shouldBe 0
    store.close()
    counting.closes.get shouldBe 1
  }

  it should "leave no handle on a file that is not a database, so the file can be replaced and reopened" in {
    // On Windows an open handle makes the delete fail; elsewhere the reopen below proves the file is usable.
    val bad = tempDir.resolve("garbage.db")
    Files.write(bad, Array.fill[Byte](4096)(0x5a.toByte))

    SQLiteMemoryStore(bad.toString).isLeft shouldBe true

    Files.delete(bad)
    val store = SQLiteMemoryStore(bad.toString).toOption.get
    store.count().toOption shouldBe Some(0L)
    store.close()
  }

  // ===== Embedding dimension mismatch =====

  "VectorMemoryStore.search" should "report an embedding dimension mismatch, naming both dimensions" in {
    withVector(MockEmbeddingService(dimensions = 32))(_.store(first))

    withVector(MockEmbeddingService(dimensions = 16)) { other =>
      val result = other.search("Scala", topK = 3)

      result.isLeft shouldBe true
      result.left.toOption.get.isInstanceOf[ConfigurationError] shouldBe true
      result.left.toOption.get.message should (include("16").and(include("32")).and(include("m1")))
      other.get(first.id).toOption.flatten.map(_.content) shouldBe Some(first.content)
    }
  }

  it should "search normally when the dimensions agree" in {
    withVector(MockEmbeddingService(dimensions = 32)) { s =>
      s.store(first).isRight shouldBe true
      s.search("Scala", topK = 3).toOption.get.map(_.memory.id) shouldBe Seq(first.id)
    }
  }

  private def withVector[A](service: EmbeddingService)(f: VectorMemoryStore => A): A = {
    val store = VectorMemoryStore(dbPath, service).fold(e => fail(e.message), identity)
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() })(_ => f(store))
  }

  // ===== Schema version =====

  "SQLiteMemoryStore" should "open a file written before schema_version existed, keep its rows, and record version 1" in {
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { c =>
      Using.resource(c.createStatement()) { st =>
        st.execute(
          "CREATE TABLE memories (id TEXT PRIMARY KEY, content TEXT NOT NULL, memory_type TEXT NOT NULL, " +
            "timestamp INTEGER NOT NULL, importance REAL, conversation_id TEXT, entity_id TEXT, source TEXT, " +
            "metadata_json TEXT DEFAULT '{}', embedding_blob BLOB)"
        )
        st.execute(
          "INSERT INTO memories (id, content, memory_type, timestamp) VALUES ('old', 'legacy', 'conversation', 1)"
        )
      }
    }

    val store = SQLiteMemoryStore(dbPath).toOption.get
    store.get(MemoryId("old")).toOption.flatten.map(_.content) shouldBe Some("legacy")
    store.close()

    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { c =>
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery("SELECT COUNT(*), MAX(version) FROM schema_version")) { rs =>
          rs.next() shouldBe true
          (rs.getInt(1), rs.getInt(2)) shouldBe ((1, 1))
        }
      }
    }
  }

  it should "keep one schema version row and all memories across reopens" in {
    (1 to 3).foreach { i =>
      val s = SQLiteMemoryStore(dbPath).toOption.get
      s.store(first.copy(id = MemoryId(s"m$i"))).isRight shouldBe true
      s.close()
    }
    val s = SQLiteMemoryStore(dbPath).toOption.get
    s.count().toOption shouldBe Some(3L)
    s.close()
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { c =>
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery("SELECT COUNT(*) FROM schema_version")) { rs =>
          rs.next() shouldBe true
          rs.getInt(1) shouldBe 1
        }
      }
    }
  }
}

/** A real connection whose `close` calls are counted, and whose statements can be made to fail. */
final private class CountingConnection(real: Connection, failStatements: Boolean) {
  val closes = new AtomicInteger(0)

  val proxy: Connection = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[Connection]),
      new InvocationHandler {
        override def invoke(target: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          if (method.getName == "close") closes.incrementAndGet()
          if (failStatements && (method.getName == "createStatement" || method.getName == "prepareStatement"))
            throw new java.sql.SQLException("schema setup failed")
          try method.invoke(real, Option(args).getOrElse(Array.empty[AnyRef]): _*)
          catch { case e: InvocationTargetException => throw e.getCause }
        }
      }
    )
    .asInstanceOf[Connection]
}
