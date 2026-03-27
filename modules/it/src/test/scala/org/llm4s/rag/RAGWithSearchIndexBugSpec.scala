package org.llm4s.rag

import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Integration tests for PostgreSQL-backed SearchIndex behavior when used through
 * `RAGConfig.withSearchIndex()`.
 *
 * These tests require PostgreSQL with pgvector to fully validate.
 * Set `PGVECTOR_TEST_URL` to enable them.
 */
class RAGWithSearchIndexBugSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres")

  private val testTableName                      = "test_rag_searchindex_bug"
  private var searchIndex: Option[PgSearchIndex] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    pgUrl.foreach { url =>
      PgSearchIndex.fromJdbcUrl(url, pgUser, pgPassword, testTableName) match {
        case Right(index) =>
          index.dropSchema()
          index.initializeSchema() match {
            case Right(_) => searchIndex = Some(index)
            case Left(e)  => println(s"Failed to initialize schema: ${e.message}")
          }
        case Left(e) =>
          println(s"Failed to create PgSearchIndex: ${e.message}")
      }
    }
  }

  override def afterAll(): Unit = {
    searchIndex.foreach { idx =>
      idx.dropSchema()
      idx.close()
    }
    super.afterAll()
  }

  private def requirePg(): Unit =
    assume(searchIndex.isDefined, "PostgreSQL with pgvector not available")

  "RAGConfig.withSearchIndex with PgSearchIndex" should "automatically configure pgVectorConnectionString" in {
    requirePg()
    val index = searchIndex.get

    val config = RAGConfig.default.withSearchIndex(index)

    config.searchIndex shouldBe defined
    config.pgVectorConnectionString shouldBe defined
    config.pgVectorUser shouldBe defined
    config.pgVectorPassword shouldBe defined
    config.pgVectorTableName shouldBe defined

    val pgCfg = index.pgConfig.get
    config.pgVectorConnectionString shouldBe Some(pgCfg.jdbcUrl)
    config.pgVectorUser shouldBe Some(pgCfg.user)
    config.pgVectorPassword shouldBe Some(pgCfg.password)
    config.pgVectorTableName shouldBe Some(pgCfg.vectorTableName)
  }

  "PgSearchIndex used via withSearchIndex" should "persist vectors that can be queried" in {
    requirePg()
    val index = searchIndex.get

    val result = for {
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("rag-bug-test"))
      )
      count <- index.ingest(
        collectionPath = CollectionPath.unsafe("rag-bug-test"),
        documentId = "test-doc",
        chunks = Seq(ChunkWithEmbedding("Test content", Array(0.1f, 0.2f, 0.3f), 0))
      )
      results <- index.query(
        auth = UserAuthorization.Admin,
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("rag-bug-test")),
        queryVector = Array(0.1f, 0.2f, 0.3f),
        topK = 10
      )
    } yield (count, results)

    result.isRight shouldBe true
    val (count, results) = result.toOption.get
    count shouldBe 1
    results.size shouldBe 1
    results.head.record.content shouldBe Some("Test content")
  }

  it should "show that RAG.stats uses in-memory when only withSearchIndex is used (documenting the current bug)" in {
    requirePg()
    searchIndex.isDefined shouldBe true
    pending
  }
}
