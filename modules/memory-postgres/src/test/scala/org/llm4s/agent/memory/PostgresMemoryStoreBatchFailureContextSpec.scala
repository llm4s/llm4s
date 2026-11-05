package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ BatchUpdateException, Connection, PreparedStatement, SQLException }
import javax.sql.DataSource

/**
 * pgjdbc's `BatchUpdateException` message holds the failing statement with its bound parameters. The store's error
 * reports the chained server error instead, in the `cause` of its context as well as in its message. The driver
 * message below has the shape pgjdbc produces for this upsert.
 */
class PostgresMemoryStoreBatchFailureContextSpec extends AnyFlatSpec with Matchers {

  private val privateContent = "private note of a neighbour row"

  private val driverMessage =
    s"Batch entry 0 INSERT INTO memories (id, content, memory_type, metadata, created_at, importance, embedding) " +
      s"VALUES (('n1'), ('$privateContent'), ('Knowledge'), ('{}')::jsonb, ('2026-11-05 17:05:40+05:30'), (NULL), " +
      "('[NaN,0.25]')::vector) ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content " +
      "was aborted: ERROR: NaN not allowed in vector  Call getNextException to see other errors in the batch."

  private val statement: PreparedStatement = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[PreparedStatement]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match {
            case "executeBatch" =>
              val failure = new BatchUpdateException(driverMessage, Array.empty[Int])
              failure.setNextException(new SQLException("ERROR: NaN not allowed in vector"))
              throw failure
            case _ => null // parameter setters, addBatch, close
          }
      }
    )
    .asInstanceOf[PreparedStatement]

  private val connection: Connection = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[Connection]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match {
            case "prepareStatement" => statement
            case _                  => null // setAutoCommit, commit, rollback, close
          }
      }
    )
    .asInstanceOf[Connection]

  private val dataSource: DataSource = Proxy
    .newProxyInstance(
      getClass.getClassLoader,
      Array(classOf[DataSource]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match {
            case "getConnection" => connection
            case other           => throw new UnsupportedOperationException(other)
          }
      }
    )
    .asInstanceOf[DataSource]

  "PostgresMemoryStore.storeAll" should "keep the failed batch's bound parameters out of the whole error" in {
    val store  = new PostgresMemoryStore(dataSource, "memories", None)
    val memory = Memory(MemoryId("n1"), privateContent, MemoryType.Knowledge, embedding = Some(Array(Float.NaN, 0.25f)))

    val error = store.storeAll(Seq(memory)).left.toOption.get

    error.message should include("NaN not allowed in vector")
    (error.message should not).include(privateContent)
    error.context.getOrElse("cause", "") should include("NaN not allowed in vector")
    (error.context.getOrElse("cause", "") should not).include(privateContent)
    (error.formatted should not).include(privateContent)
  }
}
