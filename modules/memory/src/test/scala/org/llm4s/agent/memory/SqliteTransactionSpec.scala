package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.sql.{ Connection, DriverManager, SQLException }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * `SqliteTransaction.immediate` on real connections to a temporary file database: commit, rollback
 * (for exceptions and fatal errors alike), the connection's state afterwards, and a busy database.
 */
class SqliteTransactionSpec extends AnyFlatSpec with Matchers {

  /** A temporary database file and the connections opened on it; closing closes every connection first. */
  final private class Db extends AutoCloseable {
    val file: File     = File.createTempFile("sqlite-transaction", ".db")
    private val opened = ArrayBuffer.empty[Connection]

    def connect(): Connection = {
      val connection = DriverManager.getConnection(s"jdbc:sqlite:${file.getAbsolutePath}")
      opened += connection: Unit
      connection
    }

    override def close(): Unit = {
      opened.foreach(connection => Try(connection.close()): Unit)
      file.delete(): Unit
      new File(file.getAbsolutePath + "-journal").delete(): Unit
    }
  }

  private def withDb(test: Db => Unit): Unit = Using.resource(new Db)(test)

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement())(_.execute(sql): Unit)

  private def insert(connection: Connection, value: Int): Unit =
    execute(connection, s"INSERT INTO items (v) VALUES ($value)")

  private def values(connection: Connection): Seq[Int] =
    Using.resource(connection.createStatement()) { statement =>
      Using.resource(statement.executeQuery("SELECT v FROM items ORDER BY v")) { rows =>
        val found = ArrayBuffer.empty[Int]
        while (rows.next()) found += rows.getInt(1): Unit
        found.toList
      }
    }

  private def withTable(test: (Db, Connection) => Unit): Unit =
    withDb { db =>
      val connection = db.connect()
      execute(connection, "CREATE TABLE items (v INTEGER)")
      test(db, connection)
    }

  "SqliteTransaction.immediate" should "return the block's value and commit its rows" in {
    withTable { (db, connection) =>
      val result = SqliteTransaction.immediate(connection) {
        insert(connection, 1)
        insert(connection, 2)
        "done"
      }

      result shouldBe "done"
      values(db.connect()) shouldBe Seq(1, 2)
    }
  }

  it should "propagate the block's exception unchanged and roll back its rows" in {
    withTable { (db, connection) =>
      val boom = new IllegalStateException("boom")

      val thrown = intercept[IllegalStateException] {
        SqliteTransaction.immediate(connection) {
          insert(connection, 1)
          throw boom
        }
      }

      thrown.eq(boom) shouldBe true
      values(db.connect()) shouldBe empty
      values(connection) shouldBe empty
    }
  }

  it should "roll back and propagate a fatal error" in {
    withTable { (db, connection) =>
      val fatal = new LinkageError("test")

      val thrown = intercept[LinkageError] {
        SqliteTransaction.immediate(connection) {
          insert(connection, 1)
          throw fatal
        }
      }

      thrown.eq(fatal) shouldBe true
      values(db.connect()) shouldBe empty
      values(connection) shouldBe empty
    }
  }

  it should "leave the connection in autocommit mode and usable after a commit" in {
    withTable { (db, connection) =>
      SqliteTransaction.immediate(connection)(insert(connection, 1))

      connection.getAutoCommit shouldBe true
      SqliteTransaction.immediate(connection)(insert(connection, 2))
      values(db.connect()) shouldBe Seq(1, 2)
    }
  }

  it should "leave the connection in autocommit mode and usable after a rollback" in {
    withTable { (db, connection) =>
      intercept[IllegalStateException] {
        SqliteTransaction.immediate(connection) {
          insert(connection, 1)
          throw new IllegalStateException("boom")
        }
      }

      connection.getAutoCommit shouldBe true
      SqliteTransaction.immediate(connection)(insert(connection, 2))
      values(db.connect()) shouldBe Seq(2)
    }
  }

  it should "fail with an SQLException on a busy database and leave the connection as it was" in {
    withTable { (db, writerA) =>
      val writerB = db.connect()
      execute(writerB, "PRAGMA busy_timeout = 50")

      execute(writerA, "BEGIN IMMEDIATE")
      var ran = false
      intercept[SQLException] {
        SqliteTransaction.immediate(writerB) {
          ran = true
          insert(writerB, 1)
        }
      }
      ran shouldBe false
      execute(writerA, "ROLLBACK")

      writerB.getAutoCommit shouldBe true
      SqliteTransaction.immediate(writerB)(insert(writerB, 2))
      values(writerA) shouldBe Seq(2)
      values(db.connect()) shouldBe Seq(2)
    }
  }
}
