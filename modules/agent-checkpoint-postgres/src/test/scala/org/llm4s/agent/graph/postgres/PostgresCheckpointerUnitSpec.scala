package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.GraphError
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.PrintWriter
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, SQLException }
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The parts of `PostgresCheckpointer` that need no server (design 4.50): its configuration, the schema's migration
 * plan and statements, the statements' confinement to the store's schema, the bounds it sets, and what it does with
 * a connection when a call fails. The store against a real server is `modules/it`'s (`@Docker`): the contract, the
 * concurrency, clock, schema and cross-process suites.
 */
class PostgresCheckpointerUnitSpec extends AnyFlatSpec with Matchers with EitherValues {

  // ---- configuration ----

  "PostgresCheckpointerConfig" should "default to schema llm4s, 5 second timeouts and a pool of 10" in {
    val c = PostgresCheckpointerConfig.default
    (c.schema, c.callTimeout, c.connectionTimeout, c.maxPoolSize) shouldBe ("llm4s", 5.seconds, 5.seconds, 10)
    c.bound shouldBe 10.seconds
    c.withSchema("agents").withCallTimeout(1.second).withConnectionTimeout(300.millis).withMaxPoolSize(3) shouldBe
      PostgresCheckpointerConfig("agents", 1.second, 300.millis, 3)
  }

  it should "refuse a schema that is not a plain lower-case identifier, and every bad bound" in {
    Vector("", "Agents", "1abc", "a-b", "a\"; DROP TABLE x; --", "a" * 64).foreach { bad =>
      withClue(bad)(PostgresCheckpointerConfig.of(schema = bad).left.value shouldBe a[ValidationError])
    }
    PostgresCheckpointerConfig.of(schema = "_ok_1").value.schema shouldBe "_ok_1"
    PostgresCheckpointerConfig.of(schema = "a" * 63).isRight shouldBe true
    val problems = PostgresCheckpointerConfig
      .of(callTimeout = 0.seconds, connectionTimeout = 100.millis, maxPoolSize = 0)
      .left
      .value
      .message
    problems should (include("callTimeout").and(include("connectionTimeout")).and(include("maxPoolSize")))
    PostgresCheckpointerConfig.of(callTimeout = (Int.MaxValue.toLong + 1).millis).isLeft shouldBe true
    PostgresCheckpointerConfig.of(connectionTimeout = (Int.MaxValue.toLong + 1).millis).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy PostgresCheckpointerConfig(maxPoolSize = 0)
    an[IllegalArgumentException] should be thrownBy PostgresCheckpointerConfig.default.withSchema("No")
  }

  // ---- the schema ----

  "The migration plan" should "apply each missing step in turn, nothing at the current version, and refuse a newer one" in {
    PostgresSchema.plan("s", 0, PostgresSchema.Current, PostgresSchema.steps).value shouldBe
      (0 until PostgresSchema.Current).toVector
    PostgresSchema.plan("s", PostgresSchema.Current, PostgresSchema.Current, PostgresSchema.steps).value shouldBe empty
    PostgresSchema.plan("s", 3, 2, PostgresSchema.steps).left.value shouldBe
      GraphError.UnsupportedStoreSchema("PostgreSQL schema \"s\"", 3, 2)
    GraphError.UnsupportedStoreSchema("PostgreSQL schema \"s\"", 3, 2).message should
      (include("version 3").and(include("2 this build supports")))
    val missing = PostgresSchema.plan("s", 0, 3, PostgresSchema.steps).left.value
    missing shouldBe a[ProcessingError]
    missing.message should include("from checkpoint schema version 1 to 2")
  }

  it should "name every table and sequence inside the configured schema, quoted" in {
    val t = PostgresSchema.Tables("agents")
    Vector(t.version, t.tokens, t.childOrder, t.threads, t.history, t.pendingWrites, t.events, t.effects).foreach {
      _ should startWith("\"agents\".checkpoint_")
    }
    val ddl = PostgresSchema.steps(0)(t)
    ddl.filter(_.startsWith("CREATE TABLE")) should have size 5
    ddl.filter(_.startsWith("CREATE SEQUENCE")) should have size 2
    ddl.foreach(statement => statement should include("\"agents\"."))
    // retention reads the database's commit time, never a producer's timestamp
    ddl.mkString should include("committed_at")
    (ddl.mkString should not).include("created_at")
    PostgresSchema.lockName("agents") shouldBe "llm4s.agent.checkpoint:agents"
    PostgresSchema.description("agents") shouldBe "PostgreSQL schema \"agents\""
  }

  "Every statement the store runs" should "reach only tables of its own schema" in {
    val t          = PostgresSchema.Tables("agents")
    val statements = sqlStatements(t)
    statements.size should be > 30
    val tableRef = """(?i)\b(?:FROM|INTO|UPDATE|JOIN)\s+(?!SET\b)([^\s(]+)""".r
    statements.foreach { sql =>
      tableRef.findAllMatchIn(sql).map(_.group(1)).foreach { name =>
        withClue(sql)(name should startWith("\"agents\".checkpoint_"))
      }
    }
    // sequences are read through their qualified names too
    Sql.nextToken(t) shouldBe "SELECT nextval('\"agents\".checkpoint_fencing_tokens')"
    Sql.nextChildOrder(t) shouldBe "SELECT nextval('\"agents\".checkpoint_child_order')"
    Sql.lockedRow(t) should endWith("FOR UPDATE")
    (Sql.row(t) should not).include("FOR UPDATE")
  }

  /** Every `Sql` statement built for `t`, by reflection, so a new one is checked without listing it here. */
  private def sqlStatements(t: PostgresSchema.Tables): Vector[String] =
    Sql.getClass.getDeclaredMethods.toVector
      .filter(m =>
        m.getReturnType == classOf[String] && m.getParameterTypes.toVector == Vector(classOf[PostgresSchema.Tables])
      )
      .map(_.invoke(Sql, t).asInstanceOf[String])

  // ---- bounds ----

  "The bounds" should "be set on the server for the transaction only, and on the driver past them" in {
    PostgresCheckpointer.settings(1500.millis) shouldBe
      "SET LOCAL statement_timeout = 1500; SET LOCAL lock_timeout = 1500; SET LOCAL idle_in_transaction_session_timeout = 1500"
    // rounded up, never to 0, which the server reads as no timeout
    PostgresCheckpointer.settings(1.micro) should include("statement_timeout = 1;")
    PostgresCheckpointer.socketTimeoutSeconds(5.seconds) shouldBe 11L
    PostgresCheckpointer.socketTimeoutSeconds(100.millis) shouldBe 1L
  }

  it should "name a timeout the server enforced as one" in {
    def failed(state: String) = new SQLException("boom", state)
    PostgresCheckpointer.describe(failed("57014")) should include("call timeout on the server")
    PostgresCheckpointer.describe(failed("55P03")) should include("lock another connection holds")
    PostgresCheckpointer.describe(failed("25P03")) should include("idle past its call timeout")
    PostgresCheckpointer.describe(new RuntimeException("wrapped", failed("57014"))) should include("call timeout")
    PostgresCheckpointer.describe(failed("23505")) shouldBe "boom"
    PostgresCheckpointer.describe(new RuntimeException()) shouldBe "java.lang.RuntimeException"
    PostgresCheckpointer.timedOut(2.seconds) should include("2 seconds")
  }

  // ---- connections ----

  "Opening a store" should "be a ProcessingError when no connection can be had, and close a pool it owns" in {
    val pool    = FakePool(_ => throw new SQLException("connection refused", "08001"))
    val refused = PostgresCheckpointer.create(pool, ownsDataSource = true).left.value
    refused shouldBe a[ProcessingError]
    refused.message should (include("connection refused").and(include("\"llm4s\"")))
    pool.closed shouldBe true
    val shared = FakePool(_ => throw new SQLException("connection refused", "08001"))
    PostgresCheckpointer.create(shared, ownsDataSource = false).isLeft shouldBe true
    shared.closed shouldBe false
  }

  it should "roll back, and discard rather than pool, a connection whose call threw" in {
    val calls = new CopyOnWriteArrayList[String]()
    val pool  = FakePool(_ => FakeConnection(calls, failOn = "pg_advisory_xact_lock"))
    val error = PostgresCheckpointer.create(pool, ownsDataSource = true).left.value
    error.message should include("lock failed")
    val made = calls.asScala.toVector
    made should contain("setAutoCommit(false)")
    made should contain("rollback")
    made should not contain "commit"
    // a call that threw leaves its connection out of the pool: aborted and closed, never handed back as it was
    made should contain("abort")
    made.last shouldBe "close"
  }

  "An unreachable database" should "fail a store's own pool fast, as a ProcessingError" in {
    val unreachable = PostgresCheckpointer.open(
      "jdbc:postgresql://127.0.0.1:1/none",
      "nobody",
      "nothing",
      PostgresCheckpointerConfig(connectionTimeout = 300.millis, maxPoolSize = 1)
    )
    unreachable.left.value shouldBe a[ProcessingError]
  }

  // ---- fakes ----

  /** A `DataSource` whose connections come from `connect`; it records whether it was closed. */
  final private case class FakePool(connect: Unit => Connection) extends DataSource with AutoCloseable {
    @volatile var closed                                          = false
    def close(): Unit                                             = closed = true
    def getConnection: Connection                                 = connect(())
    def getConnection(user: String, password: String): Connection = connect(())
    def getLogWriter: PrintWriter                                 = null
    def setLogWriter(out: PrintWriter): Unit                      = ()
    def setLoginTimeout(seconds: Int): Unit                       = ()
    def getLoginTimeout: Int                                      = 0
    def getParentLogger: Logger                                   = Logger.getGlobal
    def unwrap[T](iface: Class[T]): T                             = throw new SQLException("not a wrapper")
    def isWrapperFor(iface: Class[?]): Boolean                    = false
  }

  /** A connection that records the calls made on it and fails any statement whose SQL contains `failOn`. */
  private def FakeConnection(calls: CopyOnWriteArrayList[String], failOn: String): Connection = {
    val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
      method.getName match {
        case "setAutoCommit" => calls.add(s"setAutoCommit(${args(0)})"); null
        case "rollback"      => calls.add("rollback"); null
        case "commit"        => calls.add("commit"); null
        case "abort"         => calls.add("abort"); null
        case "close"         => calls.add("close"); null
        case "createStatement" =>
          calls.add("createStatement")
          statement(calls, failOn, "")
        case "prepareStatement" =>
          val sql = args(0).toString
          calls.add(s"prepare: $sql")
          if (sql.contains(failOn)) throw new SQLException("lock failed", "XX000")
          statement(calls, failOn, sql)
        case "isClosed" => java.lang.Boolean.FALSE
        case other      => throw new UnsupportedOperationException(other)
      }
    Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), handler).asInstanceOf[Connection]
  }

  private def statement(
    calls: CopyOnWriteArrayList[String],
    failOn: String,
    sql: String
  ): java.sql.PreparedStatement = {
    val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
      method.getName match {
        case "execute" =>
          calls.add(s"execute: ${Option(args).map(_.head).getOrElse(sql)}")
          java.lang.Boolean.FALSE
        case "close" => null
        case other   => throw new SQLException(s"$other is not faked ($failOn)")
      }
    Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[java.sql.PreparedStatement]), handler)
      .asInstanceOf[java.sql.PreparedStatement]
  }
}
