package org.llm4s.vectorstore

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions.pg.PgSuiteCleanup
import org.llm4s.security.Secret
import org.llm4s.vectorstore.testkit.{ KeywordIndexContract, VectorStoreContract }
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Postgres stores' contract suites' tables: one per case, named for this run, dropped when the case's store is
 * released, and never a table another suite uses. An object, not fields of the specs: the contract's cases are
 * registered while a suite is constructed.
 *
 * To run:
 * {{{
 * export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 * export PGVECTOR_TEST_PASSWORD=postgres
 * sbt "it/testOnly org.llm4s.vectorstore.PgVectorStoreContractSpec org.llm4s.vectorstore.PgKeywordIndexContractSpec"
 * }}}
 */
private object PgContractTables {
  val url: Option[String] = sys.env.get("PGVECTOR_TEST_URL").filter(_.nonEmpty)
  val user: String        = sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres")
  val password: Secret    = Secret(sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""))

  private val run     = System.currentTimeMillis()
  private val counter = new AtomicLong()
  private val tables  = new ConcurrentHashMap[AnyRef, Seq[String]]()

  def requireUrl(): String = {
    Tier.require(url.isDefined, "PGVECTOR_TEST_URL not set")
    url.get
  }

  /** A store over a table of its own, `open(url, base)`; `owned(base)` names every table the store creates. */
  def open[S <: AnyRef](label: String, owned: String => Seq[String])(open: (String, String) => S): S = {
    val base  = s"contract_${label}_${run}_${counter.incrementAndGet()}"
    val store = open(requireUrl(), base)
    tables.put(store, owned(base))
    store
  }

  def drop(store: AnyRef): Unit =
    Option(tables.remove(store)).foreach(own => url.foreach(u => PgSuiteCleanup.dropOwnTables(u, user, password, own)))
}

/** `PgVectorStore` against pgvector passes [[VectorStoreContract]] (#3000), but for the known failures below. */
@Docker
class PgVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {

  protected def newStore(): VectorStore =
    PgContractTables.open("v", base => Seq(base)) { (url, table) =>
      PgVectorStore(url, PgContractTables.user, PgContractTables.password, table).fold(e => fail(e.formatted), identity)
    }

  protected def releaseStore(store: VectorStore): Unit = {
    store.close()
    PgContractTables.drop(store)
  }

  override protected def knownFailures: Map[String, String] = Map(
    "search scores are (1 + cos) / 2: identical 1, orthogonal 0.5, opposite 0" ->
      "llm4s/llm4s#3006: pgvector scores are cos clamped at 0",
    "a stored zero vector is refused or never fails a search" ->
      "llm4s/llm4s#3006: a zero vector's cosine distance is NaN, and ScoredRecord throws",
    "list returns records in insertion order" -> "llm4s/llm4s#3025: list is newest first",
    "getBatch and deleteBatch take more ids than one statement's parameter limit" ->
      "llm4s/llm4s#3025: one IN (?, ...) past 65,535 bind parameters"
  )
}

/** `PgKeywordIndex` passes [[KeywordIndexContract]] (#3000), but for the known failures below. */
@Docker
class PgKeywordIndexContractSpec extends AnyFunSuite with KeywordIndexContract {

  protected def newStore(): KeywordIndex =
    PgContractTables.open("k", base => Seq(s"${base}_keyword")) { (url, table) =>
      PgKeywordIndex(url, PgContractTables.user, PgContractTables.password, table)
        .fold(e => fail(e.formatted), identity)
    }

  protected def releaseStore(index: KeywordIndex): Unit = {
    index.close()
    PgContractTables.drop(index)
  }

  override protected def knownFailures: Map[String, String] = Map(
    "unsegmented text is found by a word inside it" ->
      "llm4s/llm4s#3026: the text search parser makes a run of CJK one word"
  )
}
