package org.llm4s.agent.memory

import com.zaxxer.hikari.HikariDataSource
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet, Statement, Timestamp }
import java.time.Instant
import scala.collection.mutable

/**
 * `PostgresMemoryStore`'s `TransactionalMemoryStore` methods (design §4.31) without a database: a fake JDBC connection
 * that understands exactly the statements those methods issue, with rollback. The same contract against a real
 * Postgres is `PostgresTransactionalMemoryStoreSpec` (`modules/it`, `@Docker`).
 */
class PostgresTransactionalUnitSpec extends AnyFlatSpec with Matchers with EitherValues {

  /** One stored row: what the eviction query reads back. */
  final private case class Row(id: String, content: String, metadata: String, at: Instant, importance: Option[Double])

  /** The fake database's state; snapshotted when a transaction begins, restored by a rollback. */
  final private case class State(
    memories: Map[String, Row] = Map.empty,
    applied: Map[String, String] = Map.empty,
    fences: Map[String, Long] = Map.empty
  )

  final private class FakePostgres(table: String) {
    var state: State            = State()
    private var snapshot: State = state
    val statements              = mutable.ArrayBuffer.empty[String]
    val locks                   = mutable.ArrayBuffer.empty[String]
    def proxy[A](cls: Class[A])(f: (String, Array[AnyRef]) => AnyRef): A =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(cls),
          new InvocationHandler {
            def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = f(m.getName, args)
          }
        )
        .asInstanceOf[A]

    private def rows(values: Seq[Seq[Any]]): ResultSet = {
      var at       = -1
      var lastNull = false
      def cell(args: Array[AnyRef]): Any = {
        val v = values(at)(args(0).asInstanceOf[Integer] - 1)
        lastNull = v == null
        v
      }
      proxy(classOf[ResultSet]) {
        case ("next", _)         => at += 1; java.lang.Boolean.valueOf(at < values.size)
        case ("getString", a)    => cell(a).asInstanceOf[String]
        case ("getLong", a)      => java.lang.Long.valueOf(cell(a).asInstanceOf[Long])
        case ("getInt", a)       => Integer.valueOf(cell(a).asInstanceOf[Int])
        case ("getDouble", a)    => java.lang.Double.valueOf(Option(cell(a)).fold(0.0)(_.asInstanceOf[Double]))
        case ("getTimestamp", a) => Timestamp.from(cell(a).asInstanceOf[Instant])
        case ("wasNull", _)      => java.lang.Boolean.valueOf(lastNull)
        case ("close", _)        => null
        case (other, _)          => throw new UnsupportedOperationException(other)
      }
    }

    private def query(sql: String, params: mutable.Map[Int, Any]): ResultSet = {
      statements += sql
      val s = state
      if (sql.startsWith("SELECT pg_advisory_xact_lock")) { locks += params(1).toString; rows(Seq(Seq(true))) }
      else if (sql == s"SELECT token FROM ${table}_fences WHERE scope = ?")
        rows(s.fences.get(params(1).toString).map(Seq(_)).toSeq)
      else if (sql == s"SELECT outcome FROM ${table}_applied WHERE key = ?")
        rows(s.applied.get(params(1).toString).map(Seq(_)).toSeq)
      else if (sql == s"SELECT COUNT(*) FROM ${table}_applied") rows(Seq(Seq(s.applied.size)))
      else if (sql == s"SELECT 1 FROM $table WHERE id = ?")
        rows(s.memories.get(params(1).toString).map(_ => Seq(1)).toSeq)
      else if (sql.startsWith("SELECT id, importance, created_at, content, metadata::text"))
        rows(s.memories.values.toSeq.map(r => Seq(r.id, r.importance.orNull, r.at, r.content, r.metadata)))
      else throw new UnsupportedOperationException(sql)
    }

    private def update(sql: String, params: mutable.Map[Int, Any]): Int = {
      statements += sql
      val s = state
      if (sql.startsWith(s"INSERT INTO ${table}_fences")) {
        state = s.copy(fences = s.fences + (params(1).toString -> params(2).asInstanceOf[Long])); 1
      } else if (sql.startsWith(s"INSERT INTO ${table}_applied")) {
        state = s.copy(applied = s.applied + (params(1).toString -> params(3).toString)); 1
      } else if (sql == s"DELETE FROM ${table}_applied WHERE key = ?") {
        state = s.copy(applied = s.applied - params(1).toString); 1
      } else if (sql == s"DELETE FROM $table WHERE id = ?") {
        val id = params(1).toString
        state = s.copy(memories = s.memories - id)
        if (s.memories.contains(id)) 1 else 0
      } else if (sql.trim.startsWith(s"INSERT INTO $table")) {
        val row = Row(
          params(1).toString,
          params(2).toString,
          params(4).toString,
          params(5).asInstanceOf[Timestamp].toInstant,
          params.get(6).map(_.asInstanceOf[Double])
        )
        state = s.copy(memories = s.memories + (row.id -> row)); 1
      } else throw new UnsupportedOperationException(sql)
    }

    private def prepared(sql: String): PreparedStatement = {
      val params = mutable.Map.empty[Int, Any]
      proxy(classOf[PreparedStatement]) {
        case ("setString", a) => params(a(0).asInstanceOf[Integer]) = a(1); null
        case ("setLong", a)   => params(a(0).asInstanceOf[Integer]) = a(1).asInstanceOf[java.lang.Long].longValue; null
        case ("setDouble", a) =>
          params(a(0).asInstanceOf[Integer]) = a(1).asInstanceOf[java.lang.Double].doubleValue; null
        case ("setTimestamp", a)  => params(a(0).asInstanceOf[Integer]) = a(1); null
        case ("setNull", a)       => params.remove(a(0).asInstanceOf[Integer]); null
        case ("executeQuery", _)  => query(sql, params)
        case ("executeUpdate", _) => Integer.valueOf(update(sql, params))
        case ("close", _)         => null
        case (other, _)           => throw new UnsupportedOperationException(other)
      }
    }

    private def plain: Statement = proxy(classOf[Statement]) {
      case ("executeQuery", a) => query(a(0).toString, mutable.Map.empty)
      case ("close", _)        => null
      case (other, _)          => throw new UnsupportedOperationException(other)
    }

    val connection: Connection = proxy(classOf[Connection]) {
      case ("prepareStatement", a) => prepared(a(0).toString)
      case ("createStatement", _)  => plain
      case ("setAutoCommit", a) =>
        if (!a(0).asInstanceOf[java.lang.Boolean].booleanValue) snapshot = state
        null
      case ("commit", _)   => null
      case ("rollback", _) => state = snapshot; null
      case ("close", _)    => null
      case ("isClosed", _) => java.lang.Boolean.FALSE
      case (other, _)      => throw new UnsupportedOperationException(other)
    }
  }

  final private class FakeDataSource(db: FakePostgres) extends HikariDataSource {
    override def getConnection: Connection = db.connection
  }

  private def fresh(): (FakePostgres, PostgresMemoryStore) = {
    val db = new FakePostgres("mem")
    (db, new PostgresMemoryStore(new FakeDataSource(db), "mem", None))
  }

  private val k   = AppliedWriteKey("scope", "key")
  private val big = MemoryBounds()
  private def m(c: String, at: Long = 0L, importance: Option[Double] = None) =
    Memory(
      MemoryId(c),
      c,
      MemoryType.Conversation,
      timestamp = Instant.ofEpochSecond(1_700_000_000L + at),
      importance = importance
    )

  "PostgresMemoryStore.applyAtomically" should "write, record the outcome, and replay it without writing" in {
    val (db, store) = fresh()
    val (_, first)  = store.applyAtomically(k, 1, Seq(m("a")), Nil, big).value
    first shouldBe AppliedWrite(Vector(MemoryId("a")), Vector.empty, 0)
    db.state.memories.keySet shouldBe Set("a")
    val (_, again) = store.applyAtomically(k, 1, Seq(m("b")), Nil, big).value
    again shouldBe first
    db.state.memories.keySet shouldBe Set("a")
    db.statements.head shouldBe "SELECT pg_advisory_xact_lock(hashtext(?))"
  }

  it should "delete what is there, evict beyond the bounds, and not resurrect a deleted memory on replay" in {
    val (db, store) = fresh()
    store.applyAtomically(AppliedWriteKey("s", "seed"), 1, Seq(m("low", 1, Some(0.1)), m("x", 2)), Nil, big).value
    val (_, out) =
      store
        .applyAtomically(k, 1, Seq(m("y", 3), m("z", 4)), Seq(MemoryId("x")), MemoryBounds(2))
        .value
    out shouldBe AppliedWrite(Vector(MemoryId("y"), MemoryId("z")), Vector(MemoryId("x")), 1)
    db.state.memories.keySet shouldBe Set("y", "z")
    store.replace(Nil, Seq(MemoryId("y"))).value
    store.applyAtomically(k, 1, Seq(m("y", 3), m("z", 4)), Nil, big).value._2 shouldBe out
    db.state.memories.keySet shouldBe Set("z")
    // a replacement whose sources are partly gone writes nothing
    val late = AppliedWriteKey("s", "late")
    store.applyAtomically(late, 1, Seq(m("w")), Seq(MemoryId("z"), MemoryId("y")), big).value._2 shouldBe
      AppliedWrite(Vector.empty, Vector.empty, 0)
    db.state.memories.keySet shouldBe Set("z")
  }

  it should "refuse an older token for every operation, changing nothing" in {
    val (db, store) = fresh()
    store.applyAtomically(k, 5, Seq(m("a")), Nil, big).value
    val before = db.state
    store.applyAtomically(AppliedWriteKey("scope", "other"), 4, Seq(m("b")), Nil, big).isLeft shouldBe true
    store.appliedWrite(k, 4).isLeft shouldBe true
    store.forgetApplied(k, 4).isLeft shouldBe true
    db.state shouldBe before
  }

  it should "forget an applied key, and count what it holds" in {
    val (db, store) = fresh()
    store.applyAtomically(k, 1, Seq(m("a")), Nil, big).value
    store.appliedWriteCount.value shouldBe 1
    store.appliedWrite(k, 2).value._2.map(_.added) shouldBe Some(Vector(MemoryId("a")))
    store.forgetApplied(k, 2).value
    store.appliedWriteCount.value shouldBe 0
    store.appliedWrite(k, 2).value._2 shouldBe None
    db.state.fences shouldBe Map("scope" -> 2L)
  }

  "PostgresMemoryStore's advisory lock" should "be keyed as PostgreSQL folds the table name, so one table has one lock" in {
    val db    = new FakePostgres("Mem")
    val store = new PostgresMemoryStore(new FakeDataSource(db), "Mem", None)
    store.appliedWrite(k, 1).value
    db.locks.toVector shouldBe Vector("mem")
  }

  "PostgresMemoryStore.Config" should "accept a table name only while every name derived from it fits 63 bytes" in {
    (PostgresMemoryStore.Config(tableName = "t" * 55).tableName should have).length(55)
    an[IllegalArgumentException] should be thrownBy PostgresMemoryStore.Config(tableName = "t" * 56)
    // `<table>_applied`, the longest derived name, fits PostgreSQL's limit untruncated
    (("t" * 55) + "_applied").length should be <= 63
  }

  "PostgresMemoryStore.applyAtomically" should "fail, writing nothing, when the connection fails" in {
    val (db, _) = fresh()
    val failing: Connection = db.proxy(classOf[Connection]) {
      case ("prepareStatement" | "createStatement", _) => throw new java.sql.SQLException("connection lost")
      case _                                           => null
    }
    val store = new PostgresMemoryStore(
      new HikariDataSource { override def getConnection: Connection = failing },
      "mem",
      None
    )
    store.applyAtomically(k, 1, Seq(m("a")), Nil, big).isLeft shouldBe true
    store.replace(Seq(m("a")), Nil).isLeft shouldBe true
    store.appliedWriteCount.isLeft shouldBe true
    db.state shouldBe State()
  }
}
