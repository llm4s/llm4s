package org.llm4s.util

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/** A SQLite store's file is created owner-only on POSIX, and a shared one is reported, never changed (#3087). */
class PrivateSqliteFileSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var dir: Path = scala.compiletime.uninitialized

  private val pragmas = Set("journal_mode", "busy_timeout")

  override def beforeEach(): Unit = dir = Files.createTempDirectory("llm4s-private-sqlite")

  override def afterEach(): Unit =
    Using.resource(Files.walk(dir))(_.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p)))

  private def posix: Boolean = PrivateSqliteFile.supportsPosix(dir)

  private def mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

  private def withMode(path: Path, perms: String): Path =
    Files.createFile(path)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
    path

  /** The warnings `body` logs on this thread. */
  private def warnings(body: => Unit): List[String] =
    val logger   = LoggerFactory.getLogger(PrivateSqliteFile.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val thread   = Thread.currentThread.getName
    appender.start()
    logger.addAppender(appender)
    val outcome = Try(body)
    logger.detachAppender(appender)
    outcome.get
    appender.list.asScala.toList.filter(_.getThreadName == thread).map(_.getFormattedMessage)

  "PrivateSqliteFile.prepare" should "create a missing file empty and readable only by its owner" in {
    assume(posix, "the file system has no POSIX permissions (Windows)")
    val db = dir.resolve("store.db")
    warnings(PrivateSqliteFile.prepare(db)) shouldBe empty
    mode(db) shouldBe "rw-------"
    Files.size(db) shouldBe 0L
  }

  it should "leave a missing file to SQLite where the file system has no POSIX permissions" in {
    assume(!posix, "the file system has POSIX permissions")
    val db = dir.resolve("store.db")
    PrivateSqliteFile.prepare(db)
    Files.exists(db) shouldBe false
  }

  it should "leave an existing shared file's mode alone and warn, naming it" in {
    assume(posix, "the file system has no POSIX permissions (Windows)")
    val db     = withMode(dir.resolve("shared.db"), "rw-r--r--")
    val logged = warnings(PrivateSqliteFile.prepare(db))
    mode(db) shouldBe "rw-r--r--"
    logged should have size 1
    logged.head should include(db.toString)
    logged.head should include("chmod 600")
  }

  it should "warn about a shared WAL, shared-memory or journal file beside the database" in {
    assume(posix, "the file system has no POSIX permissions (Windows)")
    val db = withMode(dir.resolve("main.db"), "rw-------")
    PrivateSqliteFile.CompanionSuffixes.foreach { suffix =>
      val companion = withMode(dir.resolve(s"main.db$suffix"), "rw-rw----")
      val logged    = warnings(PrivateSqliteFile.prepare(db))
      Files.delete(companion)
      withClue(suffix)(logged.map(_.contains(companion.toString)) shouldBe List(true))
    }
  }

  it should "not warn about an existing owner-only file" in {
    assume(posix, "the file system has no POSIX permissions (Windows)")
    val db = withMode(dir.resolve("private.db"), "rw-------")
    warnings(PrivateSqliteFile.prepare(db)) shouldBe empty
    mode(db) shouldBe "rw-------"
  }

  it should "do nothing when the file's directory does not exist, leaving SQLite to report it" in {
    val db = dir.resolve("missing").resolve("store.db")
    noException should be thrownBy PrivateSqliteFile.prepare(db)
    Files.exists(db.getParent) shouldBe false
  }

  "PrivateSqliteFile.jdbcFileName" should "name the file as sqlite-jdbc does: pragmas removed, other parameters kept" in {
    PrivateSqliteFile.jdbcFileName("/d/s.db", pragmas) shouldBe Some("/d/s.db")
    PrivateSqliteFile.jdbcFileName("/d/s.db?journal_mode=WAL", pragmas) shouldBe Some("/d/s.db")
    PrivateSqliteFile.jdbcFileName("/d/s.db?Journal_Mode=WAL&&busy_timeout=5", pragmas) shouldBe Some("/d/s.db")
    PrivateSqliteFile.jdbcFileName("/d/store?tenant=alice", pragmas) shouldBe Some("/d/store?tenant=alice")
    // sqlite-jdbc keeps the parameters it does not know in reverse order
    PrivateSqliteFile.jdbcFileName("/d/s?a=1&journal_mode=WAL&b=2", pragmas) shouldBe Some("/d/s?b=2&a=1")
    PrivateSqliteFile.jdbcFileName("/d/s.db?", pragmas) shouldBe Some("/d/s.db")
    PrivateSqliteFile.jdbcFileName("/d/s.db?=", pragmas) shouldBe None
  }

  "PrivateSqliteFile.prepare(dbPath)" should "create the file sqlite-jdbc opens, without the pragmas it takes out" in {
    assume(posix, "the file system has no POSIX permissions (Windows)")
    val db = dir.resolve("plain.db")
    PrivateSqliteFile.prepare(s"$db?journal_mode=WAL", pragmas)
    mode(db) shouldBe "rw-------"
    Using.resource(Files.list(dir))(_.count()) shouldBe 1L
  }

  it should "create a file whose name keeps a parameter sqlite-jdbc does not know" in {
    assume(posix, "the file system has no POSIX permissions (Windows)")
    PrivateSqliteFile.prepare(s"${dir.resolve("store")}?tenant=alice", pragmas)
    mode(dir.resolve("store?tenant=alice")) shouldBe "rw-------"
    Files.exists(dir.resolve("store")) shouldBe false
  }

  it should "create nothing for an in-memory, temporary or class-path database or a file: URI" in {
    Seq(":memory:", "", s"file:${dir.resolve("uri.db")}", ":resource:x.db", s"${dir.resolve("m")}?mode=memory")
      .foreach(p => PrivateSqliteFile.prepare(p, pragmas))
    Using.resource(Files.list(dir))(_.count()) shouldBe 0L
  }
}
