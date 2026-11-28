package org.llm4s.agent.memory

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.PrintWriter
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet, SQLException, SQLFeatureNotSupportedException, Timestamp }
import java.time.Instant
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable

/**
 * What `PostgresMemoryStore` sends for each operation (#3030, #3031), over a data source that records every
 * statement and answers queries with canned rows: what is embedded on write, which reads the database limits and
 * which it streams for `matches` to decide. What the SQL selects on a real database is `modules/it`'s
 * `PostgresMemoryStoreSpec` and `PostgresMemoryStoreFilterContractSpec`.
 */
class PostgresMemoryStoreQueryPlanSpec extends AnyFlatSpec with Matchers {

  /** One statement as the store prepared it, with the parameters it bound and whether it was streamed. */
  final private case class Sent(sql: String, params: mutable.Map[Int, Any], var fetchSize: Int = 0)

  /** Records statements; every query answers `rows`, each a map of column to value. */
  private class RecordingDataSource(val rows: Seq[Map[String, Any]] = Seq.empty)(
    answer: Option[String => Seq[Map[String, Any]]] = None
  ) extends DataSource {
    val sent: mutable.ArrayBuffer[Sent] = mutable.ArrayBuffer.empty
    var autoCommitOff                   = 0
    var commits                         = 0

    private def proxy[A](cls: Class[A])(f: (String, Array[AnyRef]) => AnyRef): A =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(cls),
          new InvocationHandler {
            def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef =
              f(m.getName, if (args == null) Array() else args)
          }
        )
        .asInstanceOf[A]

    private def resultSet(sql: String): ResultSet = {
      val rows        = answer.fold(this.rows)(_(sql))
      var at          = -1
      var lastWasNull = false
      def value(column: AnyRef): Any = {
        val v = column match {
          case i: Integer => rows(at).values.toSeq(i - 1)
          case name       => rows(at).getOrElse(name.toString, null)
        }
        lastWasNull = v == null
        v
      }
      proxy(classOf[ResultSet]) {
        case ("next", _)         => at += 1; java.lang.Boolean.valueOf(at < rows.size)
        case ("getString", a)    => value(a(0)).asInstanceOf[String]
        case ("getDouble", a)    => java.lang.Double.valueOf(Option(value(a(0))).fold(0.0)(_.asInstanceOf[Double]))
        case ("getLong", a)      => java.lang.Long.valueOf(Option(value(a(0))).fold(0L)(_.asInstanceOf[Long]))
        case ("getInt", a)       => Integer.valueOf(Option(value(a(0))).fold(0)(_.asInstanceOf[Int]))
        case ("getTimestamp", a) => value(a(0)).asInstanceOf[Timestamp]
        case ("wasNull", _)      => java.lang.Boolean.valueOf(lastWasNull)
        case _                   => null
      }
    }

    private def statement(sql: String): PreparedStatement = {
      val record = Sent(sql, mutable.Map.empty)
      sent += record
      proxy(classOf[PreparedStatement]) {
        case ("setArray", a) =>
          record.params(a(0).asInstanceOf[Integer].intValue) =
            a(1).asInstanceOf[java.sql.Array].getArray.asInstanceOf[Array[AnyRef]].toSeq
          null
        case ("setString" | "setInt" | "setDouble" | "setTimestamp" | "setLong", a) =>
          record.params(a(0).asInstanceOf[Integer].intValue) = a(1); null
        case ("setNull", a)       => record.params(a(0).asInstanceOf[Integer].intValue) = null; null
        case ("setFetchSize", a)  => record.fetchSize = a(0).asInstanceOf[Integer].intValue; null
        case ("executeQuery", _)  => resultSet(sql)
        case ("executeUpdate", _) => Integer.valueOf(1)
        case ("executeBatch", _)  => Array(1)
        case _                    => null
      }
    }

    override def getConnection(): Connection =
      proxy(classOf[Connection]) {
        case ("prepareStatement", a) => statement(a(0).toString)
        case ("setAutoCommit", a)    => if (!a(0).asInstanceOf[java.lang.Boolean].booleanValue) autoCommitOff += 1; null
        case ("commit", _)           => commits += 1; null
        case ("createArrayOf", a) =>
          val elements = a(1)
          proxy(classOf[java.sql.Array]) {
            case ("getArray", _) => elements
            case _               => null
          }
        case ("createStatement", _) =>
          proxy(classOf[java.sql.Statement]) {
            case ("executeQuery", a) => resultSet(a(0).toString)
            case ("execute", a)      => sent += Sent(a(0).toString, mutable.Map.empty); java.lang.Boolean.FALSE
            case _                   => null
          }
        case _ => null
      }
    override def getConnection(username: String, password: String): Connection = getConnection()
    override def getLogWriter: PrintWriter                                     = null
    override def setLogWriter(out: PrintWriter): Unit                          = ()
    override def setLoginTimeout(seconds: Int): Unit                           = ()
    override def getLoginTimeout: Int                                          = 0
    override def getParentLogger: Logger                = throw new SQLFeatureNotSupportedException()
    override def unwrap[T](iface: Class[T]): T          = throw new SQLException("not a wrapper")
    override def isWrapperFor(iface: Class[?]): Boolean = false
  }

  /** Embeds every text to `[1, 2]`, recording what it was asked. */
  private class RecordingEmbeddings extends EmbeddingService {
    val asked: mutable.ArrayBuffer[Seq[String]]            = mutable.ArrayBuffer.empty
    override def embed(text: String): Result[Array[Float]] = Right(Array(1f, 2f))
    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = {
      asked += texts
      Right(texts.map(_ => Array(1f, 2f)))
    }
    override def dimensions: Int = 2
  }

  private val failing: EmbeddingService = new EmbeddingService {
    override def embed(text: String): Result[Array[Float]]                 = Left(ProcessingError("test", "down"))
    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = Left(ProcessingError("test", "down"))
    override def dimensions: Int                                           = 2
  }

  private def row(id: String, content: String, memoryType: String = "task"): Map[String, Any] =
    Map(
      "id"            -> id,
      "content"       -> content,
      "memory_type"   -> memoryType,
      "metadata_text" -> "{}",
      "created_at"    -> Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
      "importance"    -> null,
      "embedding"     -> null,
      "similarity"    -> 0.5
    )

  private def store(ds: DataSource, service: Option[EmbeddingService] = None) =
    new PostgresMemoryStore(ds, "t", service, ownsDataSource = false)

  private val memory = Memory(MemoryId("m"), "remember this", MemoryType.Custom("Report"))

  "PostgresMemoryStore" should "embed a memory it stores, and write a custom type with its prefix" in {
    val ds       = new RecordingDataSource()()
    val embedder = new RecordingEmbeddings
    store(ds, Some(embedder)).store(memory).isRight shouldBe true
    embedder.asked.toSeq shouldBe Seq(Seq("remember this"))
    ds.sent.head.params(3) shouldBe "custom:Report"
    ds.sent.head.params(7) shouldBe "[1.0,2.0]"
  }

  it should "embed only what has no embedding, on storeAll, replace and applyAtomically" in {
    val ds       = new RecordingDataSource()()
    val embedder = new RecordingEmbeddings
    val s        = store(ds, Some(embedder))
    val embedded = Memory(MemoryId("e"), "has one", MemoryType.Task).withEmbedding(Array(3f, 4f))
    s.storeAll(Seq(memory, embedded)).isRight shouldBe true
    s.replace(Seq(memory), Seq(MemoryId("old"))).isRight shouldBe true
    s.applyAtomically(AppliedWriteKey("scope", "k"), 1L, Seq(memory), Seq.empty, MemoryBounds()).isRight shouldBe true
    embedder.asked.toSeq shouldBe Seq(Seq("remember this"), Seq("remember this"), Seq("remember this"))
  }

  it should "replay an applied key's recorded outcome without asking the embedding service" in {
    val recorded = AppliedWrite(Vector(MemoryId("m")), Vector.empty, 0)
    val ds = new RecordingDataSource()(Some { sql =>
      if (sql.startsWith("SELECT 1 FROM t_applied")) Seq(Map("one" -> "1"))
      else if (sql.startsWith("SELECT outcome FROM t_applied"))
        Seq(Map("outcome" -> ujson.write(AppliedWrite.toJson(recorded))))
      else Seq.empty
    })
    val result = store(ds, Some(failing)).applyAtomically(
      AppliedWriteKey("scope", "k"),
      1L,
      Seq(memory),
      Seq.empty,
      MemoryBounds()
    )
    result.map(_._2) shouldBe Right(recorded)
    ds.sent.exists(_.sql.contains("INSERT INTO t ")) shouldBe false
  }

  it should "write nothing when embedding fails" in {
    val ds = new RecordingDataSource()()
    val s  = store(ds, Some(failing))
    s.store(memory).isLeft shouldBe true
    s.storeAll(Seq(memory)).isLeft shouldBe true
    s.replace(Seq(memory), Seq.empty).isLeft shouldBe true
    s.applyAtomically(AppliedWriteKey("scope", "k"), 1L, Seq(memory), Seq.empty, MemoryBounds()).isLeft shouldBe true
    ds.sent.map(_.sql).filterNot(_.startsWith("SELECT 1 FROM t_applied")) shouldBe empty
  }

  it should "let the database apply the limit when the filter is exact" in {
    val ds = new RecordingDataSource(Seq(row("a", "alpha")))()
    store(ds).recall(MemoryFilter.ByType(MemoryType.Task), 5).map(_.map(_.id.value)) shouldBe Right(Seq("a"))
    ds.sent.head.sql should endWith("LIMIT ?")
    ds.sent.head.fetchSize shouldBe 0
  }

  it should "stream the narrowed rows in a transaction and let matches decide a filter SQL cannot say" in {
    val ds = new RecordingDataSource(Seq(row("a", "Alpha"), row("b", "beta"), row("c", "ALPHA again")))()
    store(ds).recall(MemoryFilter.ContentContains("alpha"), 1).map(_.map(_.id.value)) shouldBe Right(Seq("a"))
    (ds.sent.head.sql should not).include("LIMIT")
    ds.sent.head.fetchSize shouldBe PostgresMemoryStore.FetchSize
    ds.autoCommitOff shouldBe 1

    store(ds).count(MemoryFilter.ContentContains("BETA")) shouldBe Right(1L)
    ds.sent.last.sql should startWith("SELECT id, content, memory_type, (SELECT jsonb_object_agg(")
  }

  // Codex on #3166: the identity names the cluster by its system identifier, never the address it was reached on
  it should "name its cluster, database, schema-qualified table and tenant, and refuse without the cluster" in {
    def answering(control: Boolean): String => Seq[Map[String, Any]] = sql =>
      if (sql.contains("pg_control_system"))
        if (control) Seq(Map("system_identifier" -> "7712809643900526635"))
        else throw new SQLException("permission denied for function pg_control_system")
      else if (sql.contains("current_database()")) Seq(Map("db" -> "app", "nspname" -> "public", "relname" -> "t"))
      else Seq.empty
    val allowed = new RecordingDataSource()(Some(answering(control = true)))
    new PostgresMemoryStore(allowed, "t", None, ownsDataSource = false, Some("alice")).storageIdentity shouldBe
      Right(Some("postgresql://7712809643900526635/app/public.t?tenant=alice"))
    allowed.sent.exists(_.sql.contains("inet_server_addr")) shouldBe false
    val refused = new RecordingDataSource()(Some(answering(control = false)))
    // fail closed: without the cluster two roles would see one table as two (Codex on #3166)
    val error = store(refused).storageIdentity.left.toOption.get.message
    error should include("GRANT EXECUTE ON FUNCTION pg_control_system()")
    error should include("permission denied")
    refused.sent.exists(_.sql.contains("current_database()")) shouldBe false
    store(new RecordingDataSource()()).storageIdentity.isLeft shouldBe true
  }

  it should "count with COUNT(*) when the filter is exact" in {
    val ds = new RecordingDataSource(Seq(Map("count" -> 7L)))()
    store(ds).count(MemoryFilter.Not(MemoryFilter.MinImportance(0.5))) shouldBe Right(7L)
    ds.sent.head.sql should startWith("SELECT COUNT(*) FROM t WHERE (NOT (")
    ds.sent.head.sql should endWith(") AND tenant = ?")
  }

  it should "delete by id the rows matches accepts, and with one DELETE when the filter is exact" in {
    val ds = new RecordingDataSource(Seq(row("a", "keep"), row("b", "drop me")))()
    store(ds).deleteMatching(MemoryFilter.Custom(_.content.startsWith("drop"))).isRight shouldBe true
    ds.sent.map(_.sql).last shouldBe "DELETE FROM t WHERE id = ANY(?) AND tenant = ?"
    // The candidates are locked as they are read, so a concurrent update cannot slip in before the delete
    ds.sent.head.sql should endWith("FOR UPDATE")
    ds.sent.last.params(1) shouldBe Seq("b")

    val exact = new RecordingDataSource()()
    store(exact).deleteMatching(MemoryFilter.HasMetadata("user-id")).isRight shouldBe true
    exact.sent.map(_.sql) shouldBe Seq("DELETE FROM t WHERE ((metadata -> CAST(? AS text)) IS NOT NULL) AND tenant = ?")
  }

  it should "search by keyword without an embedding service" in {
    val ds = new RecordingDataSource(Seq(row("a", "alpha beta"), row("b", "gamma"), row("c", "beta")))()
    store(ds).search("beta alpha", 5, MemoryFilter.All).map(_.map(s => s.memory.id.value -> s.score)) shouldBe
      Right(Seq("a" -> 1.0, "c" -> 0.5))
    store(ds).search("  ", 5, MemoryFilter.All) shouldBe Right(Seq.empty)
  }

  it should "search by similarity with an embedding service, streaming when matches decides" in {
    val ds = new RecordingDataSource(Seq(row("a", "alpha"), row("b", "beta")))()
    val s  = store(ds, Some(new RecordingEmbeddings))
    s.search("q", 5, MemoryFilter.All).map(_.map(_.memory.id.value)) shouldBe Right(Seq("a", "b"))
    ds.sent.last.sql should endWith("LIMIT ?")
    s.search("q", 5, MemoryFilter.Custom(_.content == "beta")).map(_.map(_.memory.id.value)) shouldBe Right(Seq("b"))
    ds.sent.last.fetchSize shouldBe PostgresMemoryStore.FetchSize
  }

  it should "refuse embedAll without an embedding service or with a batch size below one" in {
    store(new RecordingDataSource()()).embedAll().isLeft shouldBe true
    store(new RecordingDataSource()(), Some(new RecordingEmbeddings)).embedAll(0).isLeft shouldBe true
  }

  it should "embed the rows without an embedding with embedAll, a batch at a time" in {
    // The first read answers one row; the next, past it by id, answers none
    val ds =
      new RecordingDataSource()(
        Some(sql => if (sql.contains("id > ?")) Seq.empty else Seq(Map("id" -> "a", "content" -> "alpha")))
      )
    val embedder = new RecordingEmbeddings
    store(ds, Some(embedder)).embedAll(1).isRight shouldBe true
    embedder.asked.head shouldBe Seq("alpha")
    ds.sent.exists(_.sql.startsWith("UPDATE t SET embedding")) shouldBe true
    ds.sent.exists(_.sql.contains("AND id > ?")) shouldBe true
  }

  // ===== #3037, #3038 =====

  it should "type the column, index it with HNSW, and skip the index past pgvector's limit, for a declared dimension" in {
    val ds = new RecordingDataSource()()
    new PostgresMemoryStore(ds, "t", None, ownsDataSource = false, None, Some(3)).initializeSchema()
    val ddl = ds.sent.map(_.sql)
    ddl.exists(_.contains("embedding vector(3)")) shouldBe true
    ddl.exists(sql => sql.contains("ALTER COLUMN embedding TYPE vector(3)") && sql.contains("reembedAll")) shouldBe true
    ddl.exists(_.contains("USING hnsw (embedding vector_cosine_ops)")) shouldBe true

    val wide = new RecordingDataSource()()
    new PostgresMemoryStore(wide, "t", None, ownsDataSource = false, None, Some(3072)).initializeSchema()
    wide.sent.exists(_.sql.contains("hnsw")) shouldBe false
    wide.sent.exists(_.sql.contains("vector(3072)")) shouldBe true

    val untyped = new RecordingDataSource()()
    store(untyped).initializeSchema()
    untyped.sent.exists(_.sql.contains("vector(")) shouldBe false
    PostgresMemoryStore.fromDataSource(untyped, "t", embeddingDimensions = Some(0)).isLeft shouldBe true
  }

  it should "refuse a query of another dimension than the declared one, and find nothing for a zero query" in {
    val ds  = new RecordingDataSource()()
    val dim = new PostgresMemoryStore(ds, "t", Some(new RecordingEmbeddings), ownsDataSource = false, None, Some(3))
    dim.search("q", 5, MemoryFilter.All).left.toOption.get shouldBe a[org.llm4s.error.ConfigurationError]
    val zero = new RecordingEmbeddings {
      override def embed(text: String): Result[Array[Float]] = Right(Array(0f, 0f))
    }
    store(ds, Some(zero)).search("q", 5, MemoryFilter.All) shouldBe Right(Seq.empty)
    ds.sent shouldBe empty
  }

  it should "compare only the query's dimension, leave out zero vectors, and score (1 + cos) / 2" in {
    val ds = new RecordingDataSource(Seq(row("a", "alpha") + ("distance" -> 1.0)))()
    store(ds, Some(new RecordingEmbeddings)).search("q", 5, MemoryFilter.All) match {
      case Right(Seq(hit)) => hit.score shouldBe 0.5
      case other           => fail(s"unexpected: $other")
    }
    ds.sent.head.sql should include("vector_dims(embedding) = ?")
    ds.sent.head.sql should include("vector_norm(embedding) > 0")
  }

  it should "report a store of another dimension when nothing of the query's was found" in {
    def answering(dims: Int): String => Seq[Map[String, Any]] =
      sql => if (sql.contains("AS dims")) Seq(row("old", "old row") + ("dims" -> dims)) else Seq.empty
    val other = new RecordingDataSource()(Some(answering(3)))
    store(other, Some(new RecordingEmbeddings)).search("q", 5, MemoryFilter.All).left.toOption.get shouldBe
      a[org.llm4s.error.ConfigurationError]
    val same = new RecordingDataSource()(Some(answering(2)))
    store(same, Some(new RecordingEmbeddings)).search("q", 5, MemoryFilter.Custom(_ => true)) shouldBe Right(Seq.empty)
  }

  it should "send nothing for a limit or topK below one, or a blank query" in {
    val ds       = new RecordingDataSource()()
    val embedder = new RecordingEmbeddings
    val s        = store(ds, Some(embedder))
    s.recall(MemoryFilter.All, 0) shouldBe Right(Seq.empty)
    s.recent(-1, MemoryFilter.All) shouldBe Right(Seq.empty)
    s.search("q", 0, MemoryFilter.All) shouldBe Right(Seq.empty)
    s.search(" ", 5, MemoryFilter.All) shouldBe Right(Seq.empty)
    ds.sent shouldBe empty
    embedder.asked shouldBe empty
  }

  it should "bump the version on an upsert, and refuse an update that changes the id" in {
    val ds = new RecordingDataSource(Seq(row("m", "text") + ("version" -> 4L)))()
    val s  = store(ds)
    s.store(memory).isRight shouldBe true
    ds.sent.head.sql should include("version = t.version + 1")
    s.update(MemoryId("m"), _.copy(id = MemoryId("n"))).left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
    ds.sent.exists(_.sql.contains("UPDATE t SET")) shouldBe false
  }

  it should "re-embed the rows of another dimension with reembedAll, and refuse a dimension below one" in {
    val ds =
      new RecordingDataSource()(
        Some(sql => if (sql.contains("id > ?")) Seq.empty else Seq(Map("id" -> "a", "content" -> "alpha")))
      )
    val s = store(ds, Some(new RecordingEmbeddings))
    s.reembedAll(2, batchSize = 1).isRight shouldBe true
    ds.sent.head.sql should include("vector_dims(embedding) <> ?")
    ds.sent.head.params(2) shouldBe 2
    ds.sent.exists(
      _.sql.contains("AND (embedding IS NULL OR vector_dims(embedding) <> ?) AND tenant = ?")
    ) shouldBe true
    s.reembedAll(0).isLeft shouldBe true
  }
}
