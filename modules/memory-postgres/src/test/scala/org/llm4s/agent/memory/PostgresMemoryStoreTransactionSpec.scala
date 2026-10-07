package org.llm4s.agent.memory

import com.zaxxer.hikari.HikariDataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, SQLException }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * `PostgresMemoryStore.storeAll`'s transaction guard, without a database: a scripted JDBC connection records the
 * transaction calls the store makes and fails the ones a case asks it to. The same paths against a real Postgres are
 * in `PostgresMemoryStoreSpec` (`modules/it`, `@Docker`).
 */
class PostgresMemoryStoreTransactionSpec extends AnyFlatSpec with Matchers {

  private def memories(n: Int): Seq[Memory] =
    (1 to n).map(i => Memory(MemoryId(s"m$i"), s"note $i", MemoryType.Knowledge))

  /** What the scripted connection should fail. `failUpdate` is the 1-based `executeUpdate` call that throws. */
  final private case class Script(
    failAutoCommitOff: Boolean = false,
    failUpdate: Option[Int] = None,
    failCommit: Boolean = false,
    failRollback: Boolean = false
  )

  final private class ScriptedConnection(script: Script) {
    private val log     = new ConcurrentLinkedQueue[String]()
    private var updates = 0

    /** The transaction calls, in order: `setAutoCommit(b)`, `commit`, `rollback`, `close`, `executeUpdate`. */
    def calls: Seq[String] = log.asScala.toSeq

    private def statement: PreparedStatement =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(classOf[PreparedStatement]),
          new InvocationHandler {
            override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
              method.getName match {
                case "executeUpdate" =>
                  updates += 1
                  log.add("executeUpdate")
                  if (script.failUpdate.contains(updates)) throw new SQLException(s"row $updates rejected")
                  Integer.valueOf(1)
                case "close" => null
                case _       => null // parameter setters
              }
          }
        )
        .asInstanceOf[PreparedStatement]

    val connection: Connection = Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[Connection]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
            method.getName match {
              case "setAutoCommit" =>
                val on = args(0).asInstanceOf[java.lang.Boolean].booleanValue
                log.add(s"setAutoCommit($on)")
                if (!on && script.failAutoCommitOff) throw new SQLException("cannot begin")
                null
              case "commit" =>
                log.add("commit")
                if (script.failCommit) throw new SQLException("commit failed")
                null
              case "rollback" =>
                log.add("rollback")
                if (script.failRollback) throw new SQLException("rollback failed")
                null
              case "close" =>
                log.add("close")
                null
              case "prepareStatement" => statement
              case "isClosed"         => java.lang.Boolean.FALSE
              case other              => throw new UnsupportedOperationException(other)
            }
        }
      )
      .asInstanceOf[Connection]
  }

  /** A pool that never starts: it hands out the scripted connection. */
  final private class ScriptedDataSource(conn: ScriptedConnection) extends HikariDataSource {
    override def getConnection: Connection = conn.connection
  }

  private def run(script: Script, batch: Seq[Memory] = memories(3)): (Boolean, Seq[String]) = {
    val conn  = new ScriptedConnection(script)
    val store = new PostgresMemoryStore(new ScriptedDataSource(conn), "memories", None)
    val ok    = store.storeAll(batch).isRight
    (ok, conn.calls)
  }

  private def transaction(calls: Seq[String]): Seq[String] = calls.filterNot(_ == "executeUpdate")

  "PostgresMemoryStore.storeAll" should "write every row in one transaction, commit it and restore autocommit" in {
    val (ok, calls) = run(Script())
    ok shouldBe true
    calls.count(_ == "executeUpdate") shouldBe 3
    transaction(calls) shouldBe Seq("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close")
  }

  it should "roll back, restore autocommit and fail when a row's write fails" in {
    val (ok, calls) = run(Script(failUpdate = Some(2)))
    ok shouldBe false
    calls.count(_ == "executeUpdate") shouldBe 2
    transaction(calls) shouldBe Seq("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close")
  }

  it should "leave autocommit off after a failed rollback, for the pool to reset" in {
    val (ok, calls) = run(Script(failUpdate = Some(1), failRollback = true))
    ok shouldBe false
    transaction(calls) shouldBe Seq("setAutoCommit(false)", "rollback", "close")
  }

  it should "roll back and fail when the commit throws" in {
    val (ok, calls) = run(Script(failCommit = true))
    ok shouldBe false
    transaction(calls) shouldBe Seq("setAutoCommit(false)", "commit", "rollback", "setAutoCommit(true)", "close")
  }

  it should "fail without writing when it cannot turn autocommit off" in {
    val (ok, calls) = run(Script(failAutoCommitOff = true))
    ok shouldBe false
    calls should not contain "executeUpdate"
    calls should not contain "commit"
    calls.last shouldBe "close"
  }

  it should "report success when restoring autocommit fails after the commit" in {
    // The scripted connection, but restoring autocommit throws
    val failingRestore = new ScriptedConnection(Script())
    val proxy = Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[Connection]),
        new InvocationHandler {
          override def invoke(p: Any, method: Method, args: Array[AnyRef]): AnyRef =
            if (method.getName == "setAutoCommit" && args(0) == java.lang.Boolean.TRUE)
              throw new SQLException("cannot restore autocommit")
            else
              try method.invoke(failingRestore.connection, Option(args).getOrElse(Array.empty[AnyRef]): _*)
              catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
        }
      )
      .asInstanceOf[Connection]
    val dataSource = new HikariDataSource {
      override def getConnection: Connection = proxy
    }
    val store = new PostgresMemoryStore(dataSource, "memories", None)

    store.storeAll(memories(2)).isRight shouldBe true
    failingRestore.calls should contain("commit")
  }

  it should "not open a connection for an empty batch" in {
    val (ok, calls) = run(Script(), Seq.empty)
    ok shouldBe true
    calls shouldBe empty
  }

  "PostgresMemoryStore.store" should "write a single memory in autocommit mode, without a transaction" in {
    val conn  = new ScriptedConnection(Script())
    val store = new PostgresMemoryStore(new ScriptedDataSource(conn), "memories", None)
    store.store(memories(1).head).isRight shouldBe true
    conn.calls shouldBe Seq("executeUpdate", "close")
  }
}
