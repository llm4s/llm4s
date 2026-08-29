package org.llm4s.rag.permissions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SearchIndexPgConfigEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // JDBC URL generation edge cases
  // =========================================================================

  "SearchIndex.PgConfig" should "generate JDBC URL with special characters in database name" in {
    val config = SearchIndex.PgConfig(database = "my-test_db")
    config.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/my-test_db"
  }

  it should "generate JDBC URL with IPv6 host" in {
    val config = SearchIndex.PgConfig(host = "::1")
    config.jdbcUrl shouldBe "jdbc:postgresql://::1:5432/postgres"
  }

  it should "generate JDBC URL with non-standard port" in {
    val config = SearchIndex.PgConfig(port = 15432)
    config.jdbcUrl shouldBe "jdbc:postgresql://localhost:15432/postgres"
  }

  it should "handle empty password" in {
    val config = SearchIndex.PgConfig()
    config.password shouldBe ""
  }

  it should "handle non-default table names" in {
    val config = SearchIndex.PgConfig(
      vectorTableName = "custom_vectors",
      keywordTableName = "custom_keywords"
    )
    config.vectorTableName shouldBe "custom_vectors"
    config.keywordTableName shouldBe "custom_keywords"
  }

  it should "support hashCode consistency with equals" in {
    val a = SearchIndex.PgConfig(host = "h1", port = 5432, database = "db1")
    val b = SearchIndex.PgConfig(host = "h1", port = 5432, database = "db1")
    a.hashCode shouldBe b.hashCode
  }

  it should "produce different hash codes for different configs" in {
    val a = SearchIndex.PgConfig(host = "h1")
    val b = SearchIndex.PgConfig(host = "h2")
    a.hashCode should not be b.hashCode
  }

  it should "generate meaningful toString" in {
    val config = SearchIndex.PgConfig(host = "myhost", database = "mydb")
    val str    = config.toString
    str should include("myhost")
    str should include("mydb")
  }

  it should "support copy with maxPoolSize change" in {
    val original = SearchIndex.PgConfig(maxPoolSize = 10)
    val changed  = original.copy(maxPoolSize = 20)
    changed.maxPoolSize shouldBe 20
    changed.host shouldBe original.host
    changed.port shouldBe original.port
  }

  // =========================================================================
  // SearchIndex trait defaults
  // =========================================================================

  "SearchIndex" should "have pgConfig default as None" in {
    // Create a minimal mock to test the default
    val index = new SearchIndex {
      def principals: PrincipalStore   = null
      def collections: CollectionStore = null
      def query(
        auth: UserAuthorization,
        collectionPattern: CollectionPattern,
        queryVector: Array[Float],
        topK: Int,
        additionalFilter: Option[org.llm4s.vectorstore.MetadataFilter]
      ): org.llm4s.types.Result[Seq[org.llm4s.vectorstore.ScoredRecord]] = Right(Seq.empty)
      def ingest(
        collectionPath: CollectionPath,
        documentId: String,
        chunks: Seq[ChunkWithEmbedding],
        metadata: Map[String, String],
        readableBy: Set[PrincipalId]
      ): org.llm4s.types.Result[Int] = Right(0)
      def deleteDocument(collectionPath: CollectionPath, documentId: String): org.llm4s.types.Result[Long] =
        Right(0L)
      def clearCollection(collectionPath: CollectionPath): org.llm4s.types.Result[Long] = Right(0L)
      def initializeSchema(): org.llm4s.types.Result[Unit]                              = Right(())
      def dropSchema(): org.llm4s.types.Result[Unit]                                    = Right(())
      def close(): Unit                                                                 = ()
    }

    index.pgConfig shouldBe None
  }
}
