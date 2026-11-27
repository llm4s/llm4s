package org.llm4s.vectorstore

import org.llm4s.rag.permissions.{ CollectionPath, CollectionPattern, UserAuthorization }
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.vectorstore.MetadataFilter._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Every Postgres store sends the [[PgMetadataFilterSql]] condition for a filter and binds its parameters (#2996),
 * checked over a scripted JDBC connection; `PgMetadataFilterParitySpec` in `modules/it` runs it against Postgres.
 */
class PgStoresMetadataFilterSpec extends AnyWordSpec with Matchers {

  private val filter       = And(Not(Equals("lang", "en")), Or(HasKey("type"), Contains("path", "a_b")))
  private val (cond, args) = PgMetadataFilterSql(filter)

  /** The recorded statement that carries the filter, and its parameters from `from` on. */
  private def sentFilter(db: ScriptedDb, from: Int): Seq[Any] = {
    val (_, params) = db.writes.find(_._1.contains(cond)).getOrElse(fail(s"no statement contains $cond"))
    (from until from + args.size).map(params)
  }

  "PgVectorStore" should {
    "send and bind the shared filter condition in list" in {
      val db    = new ScriptedDb(_ => Seq.empty)
      val store = PgVectorStore(db.dataSource, "vectors").toOption.get
      store.list(filter = Some(filter)) shouldBe Right(Seq.empty)
      sentFilter(db, 1) shouldBe args
    }
  }

  "PgKeywordIndex" should {
    "send and bind the shared filter condition in search, after the query text" in {
      val db    = new ScriptedDb(_ => Seq.empty)
      val index = PgKeywordIndex(db.dataSource, "documents").toOption.get
      index.search("words", 5, Some(filter)) shouldBe Right(Seq.empty)
      sentFilter(db, 2) shouldBe args
    }
  }

  "PgSearchIndex" should {
    val collection = Map("id" -> 7, "path" -> "docs", "parent_path" -> null, "is_leaf" -> true, "metadata" -> "{}")
    def rows(sql: String): Seq[Map[String, Any]] = if (sql.contains("llm4s_collections")) Seq(collection) else Nil

    "send and bind the shared filter condition in query, after the collection ids" in {
      val db    = new ScriptedDb(rows)
      val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get
      val docs  = CollectionPattern.Exact(CollectionPath.unsafe("docs"))
      index.query(UserAuthorization.Admin, docs, Array(0.1f), 5, Some(filter)) shouldBe Right(Seq.empty)
      // 1: the query vector, 2: collection id 7
      sentFilter(db, 3) shouldBe args
    }

    "add no condition without a filter" in {
      val db    = new ScriptedDb(rows)
      val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get
      val docs  = CollectionPattern.Exact(CollectionPath.unsafe("docs"))
      index.keywordQuery(UserAuthorization.Admin, docs, "words", 5, None) shouldBe Right(Seq.empty)
      db.writes.exists(_._1.contains("metadata->>")) shouldBe false
    }
  }
}
