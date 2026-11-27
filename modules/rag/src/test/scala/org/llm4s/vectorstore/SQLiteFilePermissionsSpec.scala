package org.llm4s.vectorstore

import org.llm4s.rag.loader.SQLiteDocumentRegistry
import org.llm4s.util.PrivateSqliteFile
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.util.Using

/** The RAG SQLite stores create their files readable only by their owner (#3087). */
class SQLiteFilePermissionsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var dir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = dir = Files.createTempDirectory("llm4s-rag-permissions")

  override def afterEach(): Unit =
    Using.resource(Files.walk(dir))(_.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p)))

  /**
   * The mode of the file `name` while the store `open` opened on `path` (default `name`) plus `parameters` is open;
   * `open` returns its close.
   */
  private def modeOfNew(name: String, parameters: String = "", path: String = "")(open: String => () => Unit): String =
    assume(PrivateSqliteFile.supportsPosix(dir), "the file system has no POSIX permissions (Windows)")
    val db    = dir.resolve(name)
    val store = open(s"${dir.resolve(if path.isEmpty then name else path)}$parameters")
    val mode  = PosixFilePermissions.toString(Files.getPosixFilePermissions(db))
    store()
    mode

  private def opened[A](result: org.llm4s.types.Result[A]): A = result.fold(e => fail(e.message), identity)

  "SQLiteVectorStore" should "create its database file readable only by its owner" in {
    modeOfNew("vectors.db") { p =>
      val s = opened(SQLiteVectorStore(p))
      () => s.close()
    } shouldBe "rw-------"
  }

  it should "create the file sqlite-jdbc opens when the path carries URL parameters" in {
    // a pragma is taken out of the name; a parameter sqlite-jdbc does not know stays in it
    modeOfNew("pragma.db", "?journal_mode=WAL") { p =>
      val s = opened(SQLiteVectorStore(p))
      () => s.close()
    } shouldBe "rw-------"
    modeOfNew("store?tenant=alice", "?tenant=alice", "store") { p =>
      val s = opened(SQLiteVectorStore(p))
      () => s.close()
    } shouldBe "rw-------"
    Files.exists(dir.resolve("store")) shouldBe false
  }

  "SQLiteKeywordIndex" should "create its database file readable only by its owner" in {
    modeOfNew("keywords.db") { p =>
      val s = opened(SQLiteKeywordIndex(p))
      () => s.close()
    } shouldBe "rw-------"
  }

  "SQLiteDocumentRegistry" should "create its database file readable only by its owner" in {
    modeOfNew("registry.db") { p =>
      val s = opened(SQLiteDocumentRegistry(p))
      () => s.close()
    } shouldBe "rw-------"
  }
}
