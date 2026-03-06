package org.llm4s.rag

import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * Tests that validate the bug where PgSearchIndex vectors are not persisted
 * when using RAGConfig.withSearchIndex().
 *
 * Bug: When RAGConfig is configured with .withSearchIndex(pgSearchIndex),
 * the regular ingest/sync methods use hybridSearcher (which defaults to in-memory)
 * instead of routing to the SearchIndex.
 *
 * Expected behavior: When a SearchIndex is configured, ALL operations should
 * route through it (or the underlying vector store should be consistent).
 *
 * These tests require PostgreSQL with pgvector to fully validate.
 * Set PGVECTOR_TEST_URL environment variable to enable integration tests.
 */
class RAGWithSearchIndexBugSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Skip integration tests if PostgreSQL is not available
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

  // ==========================================================================
  // Unit Test: Demonstrates the architectural bug (no DB required)
  // ==========================================================================

  "RAGConfig.withSearchIndex with non-Pg SearchIndex" should "NOT set pgVectorConnectionString (expected for non-Pg)" in {
    // Create a mock search index without PostgreSQL backing
    val mockIndex = new SearchIndex {
      override def principals: PrincipalStore   = ???
      override def collections: CollectionStore = ???
      override def query(
        auth: UserAuthorization,
        collectionPattern: CollectionPattern,
        queryVector: Array[Float],
        topK: Int,
        additionalFilter: Option[MetadataFilter]
      ): Result[Seq[ScoredRecord]] = ???
      override def ingest(
        collectionPath: CollectionPath,
        documentId: String,
        chunks: Seq[ChunkWithEmbedding],
        metadata: Map[String, String],
        readableBy: Set[PrincipalId]
      ): Result[Int] = ???
      override def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] = ???
      override def clearCollection(collectionPath: CollectionPath): Result[Long]                    = ???
      override def initializeSchema(): Result[Unit]                                                 = ???
      override def dropSchema(): Result[Unit]                                                       = ???
      override def close(): Unit                                                                    = ()
      // Note: pgConfig defaults to None in the trait
    }

    val config = RAGConfig.default.withSearchIndex(mockIndex)

    // For non-PostgreSQL SearchIndex, pgVectorConnectionString remains None
    // This is expected behavior - the SearchIndex doesn't provide pgConfig
    config.searchIndex shouldBe defined
    config.pgVectorConnectionString shouldBe None
  }

  "RAGConfig.withSearchIndex with PgSearchIndex" should "automatically configure pgVector and pgKeywordTableName (FIX)" in {
    requirePg()
    val index = searchIndex.get

    val config = RAGConfig.default.withSearchIndex(index)

    // FIX: withSearchIndex now auto-configures pgVector AND pgKeyword settings from PgSearchIndex
    // so that regular RAG ingest/query use full PostgreSQL hybrid (pgvector + FTS) instead of
    // falling back to in-memory/SQLite keyword index
    config.searchIndex shouldBe defined
    config.pgVectorConnectionString shouldBe defined
    config.pgVectorUser shouldBe defined
    config.pgVectorPassword shouldBe defined
    config.pgVectorTableName shouldBe defined
    config.pgKeywordTableName shouldBe defined

    // Verify the values match the PgSearchIndex config
    val pgCfg = index.pgConfig.get
    config.pgVectorConnectionString shouldBe Some(pgCfg.jdbcUrl)
    config.pgVectorUser shouldBe Some(pgCfg.user)
    config.pgVectorPassword shouldBe Some(pgCfg.password)
    config.pgVectorTableName shouldBe Some(pgCfg.vectorTableName)
    config.pgKeywordTableName shouldBe Some(pgCfg.keywordTableName)
  }

  "RAG created with non-Pg SearchIndex" should "use in-memory storage for regular ingest (expected)" in {
    // When using a non-PostgreSQL SearchIndex, pgVectorConnectionString remains None
    // so regular ingest methods will use in-memory storage.
    // This is expected behavior - only PgSearchIndex auto-configures pgVector.

    val mockIndex = new TestableSearchIndex()

    val config = RAGConfig.default
      .withEmbeddings(EmbeddingProvider.OpenAI)
      .withSearchIndex(mockIndex)

    // For non-Pg SearchIndex, config should show:
    // - searchIndex = defined (the mock)
    // - pgVectorConnectionString = None (no auto-config since pgConfig returns None)

    config.searchIndex shouldBe defined
    config.pgVectorConnectionString shouldBe None

    // This is expected: non-Pg SearchIndex doesn't provide pgConfig,
    // so regular methods will use in-memory storage.
    // To use PostgreSQL with a custom SearchIndex, users should also call withPgVector().
  }

  // ==========================================================================
  // Integration Tests: Validate the bug with real PostgreSQL
  // ==========================================================================

  "PgSearchIndex used via withSearchIndex" should "persist vectors that can be queried" in {
    requirePg()
    val index = searchIndex.get

    // Create a collection
    val result = for {
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("rag-bug-test"))
      )

      // Ingest directly via SearchIndex (this works correctly)
      count <- index.ingest(
        collectionPath = CollectionPath.unsafe("rag-bug-test"),
        documentId = "test-doc",
        chunks = Seq(
          ChunkWithEmbedding("Test content", Array(0.1f, 0.2f, 0.3f), 0)
        )
      )

      // Query - should find the document
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

  it should "show that RAG.stats uses in-memory when only withSearchIndex is used (demonstrating the bug)" in {
    requirePg()
    // Use the searchIndex to verify it's available, but this test is pending
    searchIndex.isDefined shouldBe true

    // This test would require building a RAG with a real embedding client,
    // which is complex. The unit test above demonstrates the root cause.

    // When we have a complete fix, we could test:
    // 1. Build RAG with withSearchIndex(index)
    // 2. Call rag.ingest() or rag.sync()
    // 3. Check rag.stats() shows correct counts
    // 4. Check PostgreSQL tables have the data

    // For now, we document that this is the expected buggy behavior:
    // - ingest() writes to in-memory hybridSearcher
    // - stats() reads from in-memory hybridSearcher
    // - PostgreSQL tables remain empty
    // - ingestWithPermissions() works correctly (writes to SearchIndex/PostgreSQL)

    pending // TODO: Enable after fix with full integration test
  }

  // ==========================================================================
  // Helper classes for testing
  // ==========================================================================

  /**
   * A testable SearchIndex that tracks calls to ingest/query for verification.
   */
  class TestableSearchIndex extends SearchIndex {
    val ingestCalls: mutable.Buffer[(CollectionPath, String, Seq[ChunkWithEmbedding])] =
      mutable.Buffer.empty
    val queryCalls: mutable.Buffer[(UserAuthorization, CollectionPattern, Array[Float])] =
      mutable.Buffer.empty

    override def principals: PrincipalStore   = ???
    override def collections: CollectionStore = ???

    override def query(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryVector: Array[Float],
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] = {
      queryCalls += ((auth, collectionPattern, queryVector))
      Right(Seq.empty)
    }

    override def ingest(
      collectionPath: CollectionPath,
      documentId: String,
      chunks: Seq[ChunkWithEmbedding],
      metadata: Map[String, String],
      readableBy: Set[PrincipalId]
    ): Result[Int] = {
      ingestCalls += ((collectionPath, documentId, chunks))
      Right(chunks.size)
    }

    override def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] =
      Right(0L)

    override def clearCollection(collectionPath: CollectionPath): Result[Long] =
      Right(0L)

    override def initializeSchema(): Result[Unit] = Right(())
    override def dropSchema(): Result[Unit]       = Right(())
    override def close(): Unit                    = ()
  }
}
