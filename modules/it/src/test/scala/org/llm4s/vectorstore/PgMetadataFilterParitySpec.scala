package org.llm4s.vectorstore

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions.{ ChunkWithEmbedding, CollectionConfig, CollectionPath, CollectionPattern }
import org.llm4s.rag.permissions.UserAuthorization
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.vectorstore.MetadataFilter._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `PgVectorStore`, `PgKeywordIndex` and `PgSearchIndex` select the same records as the reference backend,
 * `SQLiteVectorStore`, for every filter of a matrix (#2996): `HasKey` runs, `Not` keeps a record that lacks the
 * key, `Contains` is a literal substring test, and an empty `In` or `Not(All)` matches nothing.
 *
 * To run:
 *   export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 *   export PGVECTOR_TEST_PASSWORD=postgres
 *   sbt "it/testOnly org.llm4s.vectorstore.PgMetadataFilterParitySpec"
 */
@Docker
class PgMetadataFilterParitySpec extends AnyWordSpec with Matchers {

  private val testUrl  = sys.env.get("PGVECTOR_TEST_URL")
  private val user     = sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres")
  private val password = Secret(sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""))
  private val suffix   = System.currentTimeMillis()

  private val records: Seq[(String, Map[String, String])] = Seq(
    "r1" -> Map("lang" -> "en", "path" -> "a_b", "type" -> "doc"),
    "r2" -> Map("lang" -> "fr", "path" -> "axb"),
    "r3" -> Map("path" -> "A_B", "type" -> "code"),
    "r4" -> Map("lang" -> "en", "tag" -> "50%"),
    "r5" -> Map.empty
  )

  private val filters: Seq[MetadataFilter] = Seq(
    All,
    Equals("lang", "en"),
    Not(Equals("lang", "en")),
    Contains("path", "a_b"),
    Contains("path", "_"),
    Contains("path", "A_B"),
    Contains("tag", "%"),
    Not(Contains("path", "x")),
    HasKey("type"),
    Not(HasKey("lang")),
    In("lang", Set.empty),
    Not(In("lang", Set.empty)),
    In("lang", Set("en", "fr")),
    Not(In("lang", Set("fr"))),
    Not(All),
    Or(Equals("lang", "fr"), Not(HasKey("lang"))),
    And(HasKey("path"), Not(Contains("path", "x")))
  )

  private lazy val expected: Map[MetadataFilter, Set[String]] = {
    val reference = SQLiteVectorStore.inMemory().toOption.get
    try {
      reference.upsertBatch(records.map { case (id, m) => VectorRecord(id, Array(1.0f, 0.0f), metadata = m) })
      filters.map(f => f -> reference.list(filter = Some(f)).toOption.get.map(_.id).toSet).toMap
    } finally reference.close()
  }

  private def requirePg(): Unit = Tier.require(testUrl.isDefined, "PGVECTOR_TEST_URL not set")

  private def checkAll(select: MetadataFilter => Result[Set[String]]): Unit =
    filters.foreach(f => withClue(f)(select(f) shouldBe Right(expected(f))))

  "PgVectorStore" should {
    "select the same records as SQLiteVectorStore in list, count and deleteByFilter" in {
      requirePg()
      val store = PgVectorStore(testUrl.get, user, password, s"filter_parity_v_$suffix").toOption.get
      try {
        val all = records.map { case (id, m) => VectorRecord(id, Array(1.0f, 0.0f), metadata = m) }
        store.upsertBatch(all) shouldBe Right(())
        checkAll(f => store.list(filter = Some(f)).map(_.map(_.id).toSet))
        checkAll(f => store.search(Array(1.0f, 0.0f), 10, Some(f)).map(_.map(_.record.id).toSet))
        filters.foreach(f => withClue(f)(store.count(Some(f)) shouldBe Right(expected(f).size.toLong)))
        filters.foreach { f =>
          withClue(f) {
            store.deleteByFilter(f) shouldBe Right(expected(f).size.toLong)
            store.upsertBatch(all) shouldBe Right(())
          }
        }
      } finally {
        store.clear()
        store.close()
      }
    }
  }

  "PgKeywordIndex" should {
    "select the same records as SQLiteVectorStore in search" in {
      requirePg()
      val index = PgKeywordIndex(testUrl.get, user, password, s"filter_parity_k_$suffix").toOption.get
      try {
        index.indexBatch(records.map { case (id, m) => KeywordDocument(id, "common words", m) }) shouldBe Right(())
        checkAll(f => index.search("common", 10, Some(f)).map(_.map(_.id).toSet))
        checkAll(f => index.searchWithHighlights("common", 10, filter = Some(f)).map(_.map(_.id).toSet))
      } finally {
        index.clear()
        index.close()
      }
    }
  }

  "PgSearchIndex" should {
    "select the same records as SQLiteVectorStore in query and keywordQuery" in {
      requirePg()
      val index = PgSearchIndex.fromJdbcUrl(testUrl.get, user, password, s"filter_parity_s_$suffix").toOption.get
      try {
        index.dropSchema()
        index.initializeSchema() shouldBe Right(())
        val path = CollectionPath.unsafe("parity")
        index.collections.create(CollectionConfig.publicLeaf(path)).isRight shouldBe true
        records.foreach { case (id, m) =>
          index.ingest(path, id, Seq(ChunkWithEmbedding("common words", Array(1.0f, 0.0f), 0)), m, Set.empty) shouldBe
            a[Right[?, ?]]
        }
        val pattern                              = CollectionPattern.Exact(path)
        def docIds(ms: Seq[Map[String, String]]) = ms.flatMap(_.get("docId")).toSet
        checkAll { f =>
          index
            .query(UserAuthorization.Admin, pattern, Array(1.0f, 0.0f), 10, Some(f))
            .map(r => docIds(r.map(_.record.metadata)))
        }
        checkAll { f =>
          index
            .query(UserAuthorization.Anonymous, pattern, Array(1.0f, 0.0f), 10, Some(f))
            .map(r => docIds(r.map(_.record.metadata)))
        }
        checkAll(f =>
          index
            .keywordQuery(UserAuthorization.Admin, pattern, "common", 10, Some(f))
            .map(r => docIds(r.map(_.metadata)))
        )
      } finally {
        index.dropSchema()
        index.close()
      }
    }
  }
}
