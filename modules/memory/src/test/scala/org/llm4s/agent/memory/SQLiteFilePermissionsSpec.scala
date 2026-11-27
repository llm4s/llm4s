package org.llm4s.agent.memory

import org.llm4s.util.PrivateSqliteFile
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The memory stores' SQLite files are created readable only by their owner (#3087). */
class SQLiteFilePermissionsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var dir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = dir = Files.createTempDirectory("llm4s-memory-permissions")

  override def afterEach(): Unit =
    Using.resource(Files.walk(dir))(_.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p)))

  private def mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

  "SQLiteMemoryStore" should "create its database file readable only by its owner" in {
    assume(PrivateSqliteFile.supportsPosix(dir), "the file system has no POSIX permissions (Windows)")
    val db    = dir.resolve("memories.db")
    val store = SQLiteMemoryStore(db.toString).fold(e => fail(e.message), identity)
    val got   = mode(db)
    store.close()
    got shouldBe "rw-------"
  }

  it should "create the file sqlite-jdbc opens when the path carries URL parameters" in {
    assume(PrivateSqliteFile.supportsPosix(dir), "the file system has no POSIX permissions (Windows)")
    // a pragma is taken out of the name; a parameter sqlite-jdbc does not know stays in it
    val withPragma =
      SQLiteMemoryStore(s"${dir.resolve("pragma.db")}?journal_mode=WAL").fold(e => fail(e.message), identity)
    val withOther = SQLiteMemoryStore(s"${dir.resolve("store")}?tenant=alice").fold(e => fail(e.message), identity)
    val modes     = Seq("pragma.db", "store?tenant=alice").map(name => mode(dir.resolve(name)))
    val stray     = Files.exists(dir.resolve("store"))
    withPragma.close()
    withOther.close()
    modes shouldBe Seq("rw-------", "rw-------")
    stray shouldBe false
  }

  it should "create the file sqlite-jdbc opens when the path ends in whitespace or a name starts with it" in {
    assume(PrivateSqliteFile.supportsPosix(dir), "the file system has no POSIX permissions (Windows)")
    // sqlite-jdbc trims the URL, so trailing whitespace is not in the name; a space inside the path is. (A space
    // before an absolute path makes it relative to the working directory, for sqlite-jdbc and this check alike:
    // PrivateSqliteFileSpec covers that, without creating files there.)
    val trailing = SQLiteMemoryStore(s"${dir.resolve("trailing.db")} \t").fold(e => fail(e.message), identity)
    val leading  = SQLiteMemoryStore(s"${dir.resolve(" leading.db")}").fold(e => fail(e.message), identity)
    val modes    = Seq("trailing.db", " leading.db").map(name => mode(dir.resolve(name)))
    val names    = Using.resource(Files.list(dir))(_.iterator.asScala.map(_.getFileName.toString).toSet)
    trailing.close()
    leading.close()
    modes shouldBe Seq("rw-------", "rw-------")
    names shouldBe Set("trailing.db", " leading.db")
  }

  "VectorMemoryStore" should "create its database file readable only by its owner" in {
    assume(PrivateSqliteFile.supportsPosix(dir), "the file system has no POSIX permissions (Windows)")
    val db    = dir.resolve("vectors.db")
    val store = VectorMemoryStore(db.toString, MockEmbeddingService(8)).fold(e => fail(e.message), identity)
    val got   = mode(db)
    store.close()
    got shouldBe "rw-------"
  }
}
