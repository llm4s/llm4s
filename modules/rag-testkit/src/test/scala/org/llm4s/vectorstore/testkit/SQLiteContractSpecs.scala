package org.llm4s.vectorstore.testkit

import org.llm4s.vectorstore.{ KeywordIndex, SQLiteKeywordIndex, SQLiteVectorStore, VectorStore }
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{ Files, Path }
import java.util.concurrent.ConcurrentHashMap
import scala.util.Using

/** The issues the SQLite stores' known failures are tracked in, shared by the in-memory and file-backed specs. */
private object SQLiteKnownFailures {

  val vectorStore: Map[String, String] = Map(
    "list returns records in insertion order" -> "llm4s/llm4s#3025: list is newest first"
  )

  val keywordIndex: Map[String, String] = Map(
    "unsegmented text is found by a word inside it" ->
      "llm4s/llm4s#3026: unicode61 makes a run of CJK one token"
  )
}

/**
 * A temporary directory per store, deleted when the store is released. An object, not a field of the spec: the
 * contract's cases are registered while the suite is constructed.
 */
private object TempSQLiteFiles {
  private val dirs = new ConcurrentHashMap[AnyRef, Path]()

  def create[S <: AnyRef](open: String => S): S = {
    val dir   = Files.createTempDirectory("llm4s-rag-testkit")
    val store = open(dir.resolve("store.db").toString)
    dirs.put(store, dir)
    store
  }

  def delete(store: AnyRef): Unit =
    Option(dirs.remove(store)).foreach { dir =>
      Using.resource(Files.walk(dir)) { stream =>
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }
    }
}

/** The default backend, `VectorStoreFactory.inMemory()`, passes the contract. */
class InMemorySQLiteVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {
  protected def newStore(): VectorStore = SQLiteVectorStore.inMemory().fold(e => fail(e.formatted), identity)
  protected def releaseStore(store: VectorStore): Unit      = store.close()
  override protected def knownFailures: Map[String, String] = SQLiteKnownFailures.vectorStore
}

/** `SQLiteVectorStore` over a file passes the contract. */
class FileSQLiteVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {
  protected def newStore(): VectorStore =
    TempSQLiteFiles.create(path => SQLiteVectorStore(path).fold(e => fail(e.formatted), identity))
  protected def releaseStore(store: VectorStore): Unit      = { store.close(); TempSQLiteFiles.delete(store) }
  override protected def knownFailures: Map[String, String] = SQLiteKnownFailures.vectorStore
}

/** The default keyword backend, `KeywordIndex.inMemory()`, passes the contract. */
class InMemorySQLiteKeywordIndexContractSpec extends AnyFunSuite with KeywordIndexContract {
  protected def newStore(): KeywordIndex = SQLiteKeywordIndex.inMemory().fold(e => fail(e.formatted), identity)
  protected def releaseStore(index: KeywordIndex): Unit     = index.close()
  override protected def knownFailures: Map[String, String] = SQLiteKnownFailures.keywordIndex
}

/** `SQLiteKeywordIndex` over a file passes the contract. */
class FileSQLiteKeywordIndexContractSpec extends AnyFunSuite with KeywordIndexContract {
  protected def newStore(): KeywordIndex =
    TempSQLiteFiles.create(path => SQLiteKeywordIndex(path).fold(e => fail(e.formatted), identity))
  protected def releaseStore(index: KeywordIndex): Unit     = { index.close(); TempSQLiteFiles.delete(index) }
  override protected def knownFailures: Map[String, String] = SQLiteKnownFailures.keywordIndex
}
