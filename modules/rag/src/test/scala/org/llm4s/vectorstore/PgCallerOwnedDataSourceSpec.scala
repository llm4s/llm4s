package org.llm4s.vectorstore

import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ Constructor, Method }

/**
 * The Postgres stores and indexes over a caller-owned `javax.sql.DataSource` that is not HikariCP's (#2300): they are
 * built over it, they never close it, and they close a pool they own exactly once. The same against a real database
 * is `PgCallerOwnedDataSourceIntegrationSpec` (`modules/it`, `@Docker`).
 */
class PgCallerOwnedDataSourceSpec extends AnyFlatSpec with Matchers with EitherValues {

  "PgVectorStore.create" should "build a store over a plain DataSource and leave it open on close" in {
    val ds    = new ClosableStubDataSource()
    val store = PgVectorStore.create(ds, "docs").value
    ds.statements.exists(_.contains("CREATE TABLE IF NOT EXISTS docs")) shouldBe true
    store.close()
    store.close()
    ds.closeCount shouldBe 0
  }

  it should "close an owned AutoCloseable DataSource exactly once" in {
    val ds    = new ClosableStubDataSource()
    val store = PgVectorStore.create(ds, "docs", ownsDataSource = true).value
    store.close()
    store.close()
    ds.closeCount shouldBe 1
  }

  it should "accept an owned DataSource that cannot be closed" in {
    val ds    = new StubDataSource()
    val store = PgVectorStore.create(ds, "docs", ownsDataSource = true).value
    noException should be thrownBy store.close()
  }

  it should "report an unreachable DataSource as a Left" in {
    PgVectorStore.create(new StubDataSource(failing = true), "docs").left.value.message should include(
      "connection refused"
    )
  }

  "PgVectorStore.apply(dataSource, tableName)" should "not own the DataSource" in {
    val ds = new ClosableStubDataSource()
    PgVectorStore(ds, "docs").value.close()
    ds.closeCount shouldBe 0
  }

  "PgKeywordIndex.apply(dataSource, ...)" should "build an index over a plain DataSource and leave it open" in {
    val ds    = new ClosableStubDataSource()
    val index = PgKeywordIndex(ds, "docs", "simple").value
    index.ownsDataSource shouldBe false
    index.language shouldBe "simple"
    ds.statements.exists(_.contains("CREATE TABLE IF NOT EXISTS docs_keyword")) shouldBe true
    index.close()
    ds.closeCount shouldBe 0

    val english = PgKeywordIndex(ds, "docs").value
    english.language shouldBe "english"
    english.close()
    ds.closeCount shouldBe 0
  }

  it should "report an unreachable DataSource as a Left" in {
    val failing = new StubDataSource(failing = true)
    PgKeywordIndex(failing, "docs").left.value.message should include("connection refused")
    PgKeywordIndex(failing, "docs", "english").left.value.message should include("connection refused")
  }

  it should "validate the table name and language before connecting" in {
    val ds = new StubDataSource()
    PgKeywordIndex(ds, "bad name").isLeft shouldBe true
    PgKeywordIndex(ds, "docs", "english; DROP TABLE x").isLeft shouldBe true
    ds.connectionsOpened shouldBe 0
  }

  "HybridSearcher.pgvectorShared(dataSource, ...)" should "share a caller-owned DataSource and leave it open" in {
    val ds       = new ClosableStubDataSource()
    val searcher = HybridSearcher.pgvectorShared(ds, "vecs", "docs").value
    searcher.defaultStrategy shouldBe FusionStrategy.default
    ds.statements.exists(_.contains("CREATE TABLE IF NOT EXISTS vecs")) shouldBe true
    ds.statements.exists(_.contains("CREATE TABLE IF NOT EXISTS docs_keyword")) shouldBe true
    searcher.close()
    ds.closeCount shouldBe 0

    val weighted = HybridSearcher.pgvectorShared(ds, "vecs", "docs", FusionStrategy.WeightedScore()).value
    weighted.defaultStrategy shouldBe FusionStrategy.WeightedScore()
    weighted.close()
    ds.closeCount shouldBe 0
  }

  it should "report an unreachable DataSource as a Left" in {
    HybridSearcher.pgvectorShared(new StubDataSource(failing = true), "vecs", "docs").isLeft shouldBe true
  }

  "PgSearchIndex.fromDataSource" should "build an index over a plain DataSource and leave it open" in {
    val ds    = new ClosableStubDataSource()
    val index = PgSearchIndex.fromDataSource(ds).value
    index.pgConfig shouldBe None
    index.close()
    index.close()
    ds.closeCount shouldBe 0
    PgSearchIndex.fromDataSource(ds, "permission_vectors").isRight shouldBe true
  }

  it should "refuse a null DataSource and an invalid table name without connecting" in {
    PgSearchIndex.fromDataSource(null).left.value.message should include("must not be null")
    val ds = new StubDataSource()
    PgSearchIndex.fromDataSource(ds, "bad name").isLeft shouldBe true
    ds.connectionsOpened shouldBe 0
  }

  "PgDataSources.closeOwned" should "close an AutoCloseable DataSource and ignore any other" in {
    val closable = new ClosableStubDataSource()
    PgDataSources.closeOwned(closable)
    closable.closeCount shouldBe 1
    noException should be thrownBy PgDataSources.closeOwned(new StubDataSource())
  }

  // #2300: Hikari is an implementation detail of the config-based factories, never part of a public signature.
  "The public API of the Postgres stores" should "not mention HikariDataSource" in {
    val classes = Seq(
      classOf[PgVectorStore],
      PgVectorStore.getClass,
      classOf[PgKeywordIndex],
      PgKeywordIndex.getClass,
      classOf[HybridSearcher],
      HybridSearcher.getClass,
      classOf[PgSearchIndex],
      PgSearchIndex.getClass
    )
    val hikari = "com.zaxxer.hikari"
    def mentions(m: Method): Boolean =
      (m.getReturnType +: m.getParameterTypes.toSeq).exists(_.getName.startsWith(hikari))
    def ctorMentions(c: Constructor[?]): Boolean = c.getParameterTypes.exists(_.getName.startsWith(hikari))
    val offenders = classes.flatMap { cls =>
      cls.getMethods.filter(mentions).map(m => s"${cls.getName}.${m.getName}") ++
        cls.getConstructors.filter(ctorMentions).map(_ => s"${cls.getName}.<init>")
    }
    offenders shouldBe empty
  }
}
