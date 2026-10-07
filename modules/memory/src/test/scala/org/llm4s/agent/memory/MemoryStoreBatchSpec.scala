package org.llm4s.agent.memory

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, InvocationTargetException, Method, Proxy }
import java.nio.file.{ Files, Path }
import java.sql.{ Connection, DriverManager, PreparedStatement, Statement }
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Using

/**
 * `storeAll` on the SQLite-backed stores is one transaction: one commit for the batch, all of it or none of it.
 * It used to be a loop of `store` calls, each committing three statements of its own, which made a batch pay a file
 * sync per statement - about a second for fifteen rows on Windows - and left a failed batch half written.
 */
class MemoryStoreBatchSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = _

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("llm4s-memory-batch")

  override def afterEach(): Unit =
    Using.resource(Files.walk(tempDir))(paths =>
      paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
    )

  private def dbPath: String = tempDir.resolve("memories.db").toString

  private def connect(path: String): Connection = DriverManager.getConnection(s"jdbc:sqlite:$path")

  private def memories(n: Int): Seq[Memory] =
    (1 to n).map(i => Memory(id = MemoryId(s"m$i"), content = s"note number $i", memoryType = MemoryType.Knowledge))

  /** Five memories whose third cannot be written: `content` is NOT NULL in both schemas. */
  private def failingBatch: Seq[Memory] =
    memories(5).updated(2, memories(5)(2).copy(content = null, embedding = Some(Array.fill(8)(0.1f))))

  /** An embedding service that fails for one text. */
  private def failingOn(text: String): EmbeddingService = new EmbeddingService {
    private val real             = MockEmbeddingService(8)
    override val dimensions: Int = real.dimensions
    override def embed(t: String): Result[Array[Float]] =
      if (t == text) Left(ProcessingError("embed", s"cannot embed '$t'")) else real.embed(t)
    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
      texts.foldLeft[Result[Seq[Array[Float]]]](Right(Vector.empty))((acc, t) => acc.flatMap(v => embed(t).map(v :+ _)))
  }

  private def right[A](r: Result[A]): A = r.fold(e => fail(e.message), identity)

  /** Opens a store, runs `test` on it, and closes it even when `test` fails, so the temp directory can be deleted. */
  private case class Subject(name: String, open: (String => Connection) => Result[MemoryStore]) {
    def apply[A](connector: String => Connection = connect)(test: MemoryStore => A): A = {
      val store = right(open(connector))
      Using.resource(new AutoCloseable { override def close(): Unit = MemoryStoreBatchSpec.close(store) })(_ =>
        test(store)
      )
    }
  }

  private val fileSubjects = Seq(
    Subject("SQLiteMemoryStore (file)", c => SQLiteMemoryStore.open(dbPath, MemoryStoreConfig.default, c)),
    Subject(
      "VectorMemoryStore (file)",
      c => VectorMemoryStore.open(dbPath, MockEmbeddingService(8), MemoryStoreConfig.default, c)
    )
  )

  private val inMemorySubjects = Seq(
    Subject("SQLiteMemoryStore (in memory)", _ => SQLiteMemoryStore.inMemory()),
    Subject("VectorMemoryStore (in memory)", _ => VectorMemoryStore.inMemory(MockEmbeddingService(8)))
  )

  for (subject <- fileSubjects) {
    subject.name should "store a batch with a single commit" in {
      val commits = new CommitCounter(connect(dbPath))
      subject(_ => commits.proxy) { store =>
        commits.count.set(0)
        val stored = right(store.storeAll(memories(15)))
        commits.count.get shouldBe 1
        right(stored.count()) shouldBe 15L
      }
    }

    it should "store a single memory with a single commit, the row and its full-text entry together" in {
      val commits = new CommitCounter(connect(dbPath))
      subject(_ => commits.proxy) { store =>
        commits.count.set(0)
        val stored = right(store.store(memories(1).head))
        commits.count.get shouldBe 1
        right(stored.search("number", 10)).map(_.memory.id.value) shouldBe Seq("m1")
      }
    }

    it should "keep the last of two memories with the same id in one batch, with one full-text entry" in {
      subject() { store =>
        val first  = memories(1).head
        val stored = right(store.storeAll(Seq(first, first.copy(content = "replaced note", embedding = None))))
        right(stored.get(first.id)).map(_.content) shouldBe Some("replaced note")
        right(stored.count()) shouldBe 1L
        right(stored.search("replaced", 10)).map(_.memory.id.value) shouldBe Seq("m1")
      }
    }

    it should "store nothing from a batch whose write fails part way through, and keep working after it" in {
      subject() { store =>
        store.storeAll(failingBatch).isLeft shouldBe true
        right(store.count()) shouldBe 0L

        right(store.storeAll(memories(3)))
        right(store.store(memories(4).last))
        right(store.count()) shouldBe 4L
        // Committed, not merely visible on this connection.
        Using.resource(connect(dbPath))(other =>
          Using.resource(other.createStatement())(st =>
            Using.resource(st.executeQuery("SELECT COUNT(*) FROM memories")) { rs =>
              rs.next(); rs.getLong(1)
            }
          )
        ) shouldBe 4L
      }
    }
  }

  for (subject <- inMemorySubjects)
    subject.name should "store a batch whole, or nothing of a failing one" in {
      subject() { store =>
        store.storeAll(failingBatch).isLeft shouldBe true
        right(store.count()) shouldBe 0L
        right(store.storeAll(memories(15)))
        right(store.count()) shouldBe 15L
      }
    }

  "VectorMemoryStore" should "store nothing from a batch when one memory cannot be embedded" in {
    val store = right(VectorMemoryStore.open(dbPath, failingOn("note number 3"), MemoryStoreConfig.default, connect))
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
      store.storeAll(memories(5)).isLeft shouldBe true
      right(store.count()) shouldBe 0L
    }
  }

  "SQLiteMemoryStore" should "fail a write while another connection holds the write lock, and recover once it is released" in {
    val shortTimeout: String => Connection = path => {
      val c = connect(path)
      Using.resource(c.createStatement())(_.execute("PRAGMA busy_timeout = 100"))
      c
    }
    val store = right(SQLiteMemoryStore.open(dbPath, MemoryStoreConfig.default, shortTimeout))
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
      Using.resource(connect(dbPath)) { holder =>
        Using.resource(holder.createStatement())(_.execute("BEGIN IMMEDIATE"))
        store.store(memories(1).head).isLeft shouldBe true
        store.storeAll(memories(3)).isLeft shouldBe true
        Using.resource(holder.createStatement())(_.execute("ROLLBACK"))
      }

      right(store.storeAll(memories(3)))
      right(store.store(memories(4).last))
      Using.resource(connect(dbPath))(other =>
        Using.resource(other.createStatement())(st =>
          Using.resource(st.executeQuery("SELECT COUNT(*) FROM memories")) { rs =>
            rs.next(); rs.getLong(1)
          }
        )
      ) shouldBe 4L
    }
  }

  private def rowsSeenByAnotherConnection(): Long =
    Using.resource(connect(dbPath))(other =>
      Using.resource(other.createStatement())(st =>
        Using.resource(st.executeQuery("SELECT COUNT(*) FROM memories")) { rs =>
          rs.next(); rs.getLong(1)
        }
      )
    )

  it should "write nothing when its COMMIT fails, and recover once the reader is gone" in {
    val shortTimeout: String => Connection = path => {
      val c = connect(path)
      Using.resource(c.createStatement())(_.execute("PRAGMA busy_timeout = 100"))
      c
    }
    val store = right(SQLiteMemoryStore.open(dbPath, MemoryStoreConfig.default, shortTimeout))
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
      Using.resource(connect(dbPath)) { reader =>
        // A read transaction holds a SHARED lock (rollback-journal mode): the store's BEGIN IMMEDIATE still gets the
        // RESERVED lock and writes, but its COMMIT cannot get EXCLUSIVE and fails with SQLITE_BUSY.
        Using.resource(reader.createStatement()) { st =>
          st.execute("BEGIN")
          Using.resource(st.executeQuery("SELECT COUNT(*) FROM memories"))(rs => rs.next())
        }
        store.storeAll(memories(3)).isLeft shouldBe true
        Using.resource(reader.createStatement())(_.execute("COMMIT"))
      }

      rowsSeenByAnotherConnection() shouldBe 0L
      right(store.count()) shouldBe 0L
      right(store.storeAll(memories(3)))
      rowsSeenByAnotherConnection() shouldBe 3L
    }
  }

  // ===== VectorMemoryStore embeds in bounded batches, and replaces a memory atomically =====

  /** Embeds like the mock, but rejects a batch of more than `limit` texts, as a provider's input limit would. */
  final private class LimitedBatches(limit: Int) extends EmbeddingService {
    private val real                                    = MockEmbeddingService(8)
    val batchSizes                                      = scala.collection.mutable.ArrayBuffer.empty[Int]
    override val dimensions: Int                        = real.dimensions
    override def embed(t: String): Result[Array[Float]] = real.embed(t)
    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = {
      batchSizes += texts.size
      if (texts.size > limit) Left(ProcessingError("embed", s"${texts.size} inputs exceed the limit of $limit"))
      else real.embedBatch(texts)
    }
  }

  private def withVector[A](service: EmbeddingService)(test: VectorMemoryStore => A): A = {
    val store = right(VectorMemoryStore.open(dbPath, service, MemoryStoreConfig.default, connect))
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() })(_ => test(store))
  }

  "VectorMemoryStore.storeAll" should "embed a large batch in chunks the embedding service accepts" in {
    val service = new LimitedBatches(VectorMemoryStore.EmbeddingBatchSize)
    withVector(service) { store =>
      right(store.storeAll(memories(200)))
      right(store.count()) shouldBe 200L
      service.batchSizes.toList shouldBe List(64, 64, 64, 8)
    }
  }

  it should "store nothing when an embedding call throws" in {
    val throwing = new EmbeddingService {
      override val dimensions: Int                        = 8
      override def embed(t: String): Result[Array[Float]] = throw new IllegalStateException("embedder crashed")
      override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
        throw new IllegalStateException("embedder crashed")
    }
    withVector(throwing) { store =>
      val result = store.storeAll(memories(3))
      result.isLeft shouldBe true
      result.left.toOption.get.message should include("embedder crashed")
      right(store.count()) shouldBe 0L
    }
  }

  "VectorMemoryStore.update" should "leave the memory as it was when re-embedding its new content fails" in {
    withVector(failingOn("rewritten")) { store =>
      val original = memories(1).head
      right(store.store(original))

      store.update(original.id, _.copy(content = "rewritten")).isLeft shouldBe true

      right(store.get(original.id)).map(_.content) shouldBe Some(original.content)
      right(store.search("number", 10)).map(_.memory.id.value) shouldBe Seq("m1")
    }
  }

  it should "replace the memory, its embedding and its full-text entry when it succeeds" in {
    withVector(MockEmbeddingService(8)) { store =>
      val original = memories(1).head
      right(store.store(original))

      right(store.update(original.id, _.copy(content = "rewritten text")))

      right(store.count()) shouldBe 1L
      right(store.get(original.id)).map(_.content) shouldBe Some("rewritten text")
      right(store.search("rewritten", 10)).map(_.memory.id.value) shouldBe Seq("m1")
    }
  }
}

object MemoryStoreBatchSpec {
  private def close(store: MemoryStore): Unit = store match {
    case s: SQLiteMemoryStore => s.close()
    case s: VectorMemoryStore => s.close()
    case _                    => ()
  }
}

/**
 * A connection that counts the transactions it commits: each `COMMIT` statement or `commit()` call, and each statement
 * run outside an explicit `BEGIN` ... `COMMIT`/`ROLLBACK`, which SQLite commits on its own. Otherwise it does what the
 * real connection does.
 */
final private class CommitCounter(real: Connection) {
  val count                           = new AtomicInteger(0)
  @volatile private var inTransaction = false

  private def forward(target: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
    try method.invoke(target, Option(args).getOrElse(Array.empty[AnyRef]): _*)
    catch { case e: InvocationTargetException => throw e.getCause }

  private def executed(sql: String): Unit = {
    val s = sql.trim.toUpperCase
    if (s.startsWith("BEGIN")) inTransaction = true
    else if (s.startsWith("COMMIT") || s.startsWith("END")) { count.incrementAndGet(); inTransaction = false }
    else if (s.startsWith("ROLLBACK")) inTransaction = false
    else if (!inTransaction) count.incrementAndGet(): Unit
  }

  private def counting[A](target: AnyRef, iface: Class[A], preparedSql: Option[String]): A =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(iface),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
            if (method.getName.startsWith("execute")) {
              val sql = preparedSql.orElse(Option(args).flatMap(_.headOption).collect { case s: String => s })
              sql match {
                case Some(s) => executed(s)
                case None => throw new UnsupportedOperationException(s"CommitCounter cannot see the SQL of ${method}")
              }
            }
            forward(target, method, args)
          }
        }
      )
      .asInstanceOf[A]

  val proxy: Connection = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[Connection]),
      new InvocationHandler {
        override def invoke(target: Any, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match {
            case "commit" =>
              count.incrementAndGet()
              forward(real, method, args)
            case "setAutoCommit" if args(0) == java.lang.Boolean.FALSE =>
              throw new UnsupportedOperationException("CommitCounter counts SQL transactions, not JDBC ones")
            case "prepareStatement" =>
              counting(forward(real, method, args), classOf[PreparedStatement], Some(args(0).asInstanceOf[String]))
            case "createStatement" =>
              counting(forward(real, method, args), classOf[Statement], None)
            case _ => forward(real, method, args)
          }
      }
    )
    .asInstanceOf[Connection]
}
