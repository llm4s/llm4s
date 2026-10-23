package org.llm4s.agent.memory

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.PrintWriter
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, SQLException, SQLFeatureNotSupportedException, Statement }
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable

/**
 * `PostgresMemoryStore` over a caller-owned `javax.sql.DataSource` that is not HikariCP's (#2300): `fromDataSource`
 * builds the schema over it and never closes it, while a store that owns its pool closes it exactly once. The same
 * against a real database is `PostgresMemoryStoreDataSourceIntegrationSpec` (`modules/it`, `@Docker`).
 */
class PostgresMemoryStoreDataSourceSpec extends AnyFlatSpec with Matchers with EitherValues {

  /** Accepts the schema DDL and records it; `AutoCloseable`, as a pool is, counting its closes. */
  private class StubDataSource(failing: Boolean = false) extends DataSource with AutoCloseable {
    val statements: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    var connectionsOpened                       = 0
    var closeCount                              = 0

    private def proxy[A](cls: Class[A])(f: (String, Array[AnyRef]) => AnyRef): A =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(cls),
          new InvocationHandler {
            def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = f(m.getName, args)
          }
        )
        .asInstanceOf[A]

    private def statement: Statement = proxy(classOf[Statement]) {
      case ("execute", a) => statements += a(0).toString; java.lang.Boolean.FALSE
      case _              => null
    }

    override def getConnection(): Connection =
      if (failing) throw new SQLException("connection refused")
      else {
        connectionsOpened += 1
        proxy(classOf[Connection]) {
          case ("createStatement", _) => statement
          case _                      => null
        }
      }
    override def getConnection(username: String, password: String): Connection = getConnection()
    override def getLogWriter: PrintWriter                                     = null
    override def setLogWriter(out: PrintWriter): Unit                          = ()
    override def setLoginTimeout(seconds: Int): Unit                           = ()
    override def getLoginTimeout: Int                                          = 0
    override def getParentLogger: Logger                = throw new SQLFeatureNotSupportedException()
    override def unwrap[T](iface: Class[T]): T          = throw new SQLException("not a wrapper")
    override def isWrapperFor(iface: Class[?]): Boolean = false
    override def close(): Unit                          = closeCount += 1
  }

  "PostgresMemoryStore.fromDataSource" should "create the schema over the caller's DataSource and leave it open" in {
    val ds    = new StubDataSource()
    val store = PostgresMemoryStore.fromDataSource(ds, "mem").value
    store.tableName shouldBe "mem"
    store.ownsDataSource shouldBe false
    ds.statements.exists(_.contains("CREATE TABLE IF NOT EXISTS mem ")) shouldBe true
    ds.statements.exists(_.contains("CREATE TABLE IF NOT EXISTS mem_applied")) shouldBe true
    store.close()
    store.close()
    ds.closeCount shouldBe 0
  }

  it should "default the table name to agent_memories" in {
    PostgresMemoryStore.fromDataSource(new StubDataSource()).value.tableName shouldBe "agent_memories"
  }

  it should "refuse a null DataSource and an invalid table name without connecting" in {
    PostgresMemoryStore.fromDataSource(null).left.value.message should include("must not be null")
    val ds = new StubDataSource()
    PostgresMemoryStore.fromDataSource(ds, "bad name").left.value.message should include("Invalid table name")
    PostgresMemoryStore.fromDataSource(ds, null).isLeft shouldBe true
    PostgresMemoryStore.fromDataSource(ds, "t" * 56).isLeft shouldBe true
    ds.connectionsOpened shouldBe 0
  }

  it should "report an unreachable DataSource as a Left and leave it open" in {
    val ds = new StubDataSource(failing = true)
    PostgresMemoryStore.fromDataSource(ds).left.value.message should include("connection refused")
    ds.closeCount shouldBe 0
  }

  "A store that owns its DataSource" should "close it exactly once" in {
    val ds    = new StubDataSource()
    val store = new PostgresMemoryStore(ds, "mem", None, ownsDataSource = true)
    store.close()
    store.close()
    ds.closeCount shouldBe 1
  }

  it should "accept an owned DataSource that cannot be closed" in {
    val plain = new DataSource {
      override def getConnection(): Connection                                   = null
      override def getConnection(username: String, password: String): Connection = null
      override def getLogWriter: PrintWriter                                     = null
      override def setLogWriter(out: PrintWriter): Unit                          = ()
      override def setLoginTimeout(seconds: Int): Unit                           = ()
      override def getLoginTimeout: Int                                          = 0
      override def getParentLogger: Logger                = throw new SQLFeatureNotSupportedException()
      override def unwrap[T](iface: Class[T]): T          = throw new SQLException("not a wrapper")
      override def isWrapperFor(iface: Class[?]): Boolean = false
    }
    noException should be thrownBy new PostgresMemoryStore(plain, "mem", None, ownsDataSource = true).close()
  }

  "PostgresMemoryStore.apply" should "report an unreachable database as a Left" in {
    PostgresMemoryStore(PostgresMemoryStore.Config(port = 1, tableName = "mem")).left.value.message should include(
      "Failed to initialize"
    )
  }

  // #2300: Hikari is an implementation detail of the config-based factory, never part of a public signature.
  "PostgresMemoryStore's public API" should "not mention HikariDataSource" in {
    val offenders = Seq(classOf[PostgresMemoryStore], PostgresMemoryStore.getClass).flatMap { cls =>
      cls.getMethods.toSeq
        .filter(m => (m.getReturnType +: m.getParameterTypes.toSeq).exists(_.getName.startsWith("com.zaxxer.hikari")))
        .map(m => s"${cls.getName}.${m.getName}")
    }
    offenders shouldBe empty
  }
}
