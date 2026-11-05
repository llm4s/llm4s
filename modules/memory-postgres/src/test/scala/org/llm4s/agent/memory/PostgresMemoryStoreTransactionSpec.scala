package org.llm4s.agent.memory

import com.zaxxer.hikari.HikariDataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ BatchUpdateException, Connection, PreparedStatement, SQLException }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * `PostgresMemoryStore.storeAll`'s transaction guard and JDBC batching, without a database: a scripted JDBC connection
 * records the transaction and batch calls the store makes and fails the ones a case asks it to. The same paths against a real Postgres are
 * in `PostgresMemoryStoreSpec` (`modules/it`, `@Docker`).
 */
class PostgresMemoryStoreTransactionSpec extends AnyFlatSpec with Matchers {

  private def memories(n: Int): Seq[Memory] =
    (1 to n).map(i => Memory(MemoryId(s"m$i"), s"note $i", MemoryType.Knowledge))

  /**
   * What the scripted connection should fail. `failBatch` is the 1-based `executeBatch` call that throws a
   * `BatchUpdateException`, chaining a server error unless `bareBatchFailure`.
   */
  final private case class Script(
    failAutoCommitOff: Boolean = false,
    failBatch: Option[Int] = None,
    bareBatchFailure: Boolean = false,
    failCommit: Boolean = false,
    failRollback: Boolean = false
  )

  final private class ScriptedConnection(script: Script) {
    private val log     = new ConcurrentLinkedQueue[String]()
    private val bound   = new ConcurrentLinkedQueue[(String, String)]()
    private var batches = 0
    private var pending = 0
    private var row     = Map.empty[Int, String]

    /**
     * The transaction and batch calls, in order: `setAutoCommit(b)`, `commit`, `rollback`, `close`, and
     * `executeBatch(n)` for a batch of `n` rows.
     */
    def calls: Seq[String] = log.asScala.toSeq

    /** The (id, content) of every row added to a batch, in order. */
    def rows: Seq[(String, String)] = bound.asScala.toSeq

    private def statement: PreparedStatement =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(classOf[PreparedStatement]),
          new InvocationHandler {
            override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
              method.getName match {
                case "setString" =>
                  row = row.updated(args(0).asInstanceOf[Integer].intValue, args(1).asInstanceOf[String])
                  null
                case "addBatch" =>
                  bound.add(row(1) -> row(2))
                  pending += 1
                  null
                case "executeBatch" =>
                  batches += 1
                  log.add(s"executeBatch($pending)")
                  val size = pending
                  pending = 0
                  if (script.failBatch.contains(batches)) {
                    val failure = new BatchUpdateException(s"Batch entry 0 INSERT ... was aborted", Array.empty[Int])
                    if (!script.bareBatchFailure) failure.setNextException(new SQLException(s"batch $batches rejected"))
                    throw failure
                  }
                  Array.fill(size)(1)
                case "executeUpdate" => throw new UnsupportedOperationException("writes are batched")
                case _               => null // other parameter setters, close
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
    val (result, conn) = runWith(script, batch)
    (result.isRight, conn.calls)
  }

  private def runWith(script: Script, batch: Seq[Memory]): (Either[String, Unit], ScriptedConnection) = {
    val conn  = new ScriptedConnection(script)
    val store = new PostgresMemoryStore(new ScriptedDataSource(conn), "memories", None)
    (store.storeAll(batch).left.map(_.message).map(_ => ()), conn)
  }

  private def transaction(calls: Seq[String]): Seq[String] = calls.filterNot(_.startsWith("executeBatch"))

  "PostgresMemoryStore.storeAll" should "write every row in one transaction, commit it and restore autocommit" in {
    val (ok, calls) = run(Script())
    ok shouldBe true
    calls shouldBe Seq("setAutoCommit(false)", "executeBatch(3)", "commit", "setAutoCommit(true)", "close")
  }

  it should "send a large batch as JDBC batches of at most WriteBatchSize rows" in {
    val size        = PostgresMemoryStore.WriteBatchSize
    val (ok, calls) = run(Script(), memories(2 * size + 5))
    val batchesSent = calls.filter(_.startsWith("executeBatch"))
    ok shouldBe true
    batchesSent shouldBe Seq(s"executeBatch($size)", s"executeBatch($size)", "executeBatch(5)")
    transaction(calls) shouldBe Seq("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close")
  }

  it should "write a repeated id once, as its last occurrence" in {
    val m1             = Memory(MemoryId("a"), "first", MemoryType.Knowledge)
    val m2             = Memory(MemoryId("b"), "other", MemoryType.Knowledge)
    val m3             = Memory(MemoryId("a"), "last", MemoryType.Knowledge)
    val (result, conn) = runWith(Script(), Seq(m1, m2, m3))
    result shouldBe Right(())
    conn.rows shouldBe Seq("a" -> "last", "b" -> "other")
  }

  it should "roll back, restore autocommit and fail when a batch fails, reporting the server's error" in {
    val size           = PostgresMemoryStore.WriteBatchSize
    val (result, conn) = runWith(Script(failBatch = Some(2)), memories(size + 1))
    result.left.toOption.get should (include("batch 2 rejected").and(not).include("INSERT"))
    conn.calls.filter(_.startsWith("executeBatch")) shouldBe Seq(s"executeBatch($size)", "executeBatch(1)")
    transaction(conn.calls) shouldBe Seq("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close")
  }

  it should "report a batch failure's own message when it chains no server error" in {
    val (result, _) = runWith(Script(failBatch = Some(1), bareBatchFailure = true), memories(2))
    result.left.toOption.get should include("was aborted")
  }

  it should "leave autocommit off after a failed rollback, for the pool to reset" in {
    val (ok, calls) = run(Script(failBatch = Some(1), failRollback = true))
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
    calls.filter(_.startsWith("executeBatch")) shouldBe empty
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
    conn.calls shouldBe Seq("executeBatch(1)", "close")
  }
}
