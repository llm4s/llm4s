package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach
import org.llm4s.error.ProcessingError
import scala.util.Try

/**
 * Tests for PgKeywordIndex.
 *
 * These tests require a running PostgreSQL database (16+, 18+ recommended).
 * Set environment variable PGVECTOR_TEST_URL to enable tests, e.g.:
 *   export PGVECTOR_TEST_URL="jdbc:postgresql://localhost:5432/postgres"
 *
 * To set up PostgreSQL for testing:
 *   docker run -d --name pg-hybrid -e POSTGRES_PASSWORD=test -p 5432:5432 pgvector/pgvector:pg18
 *
 * No additional extensions are required beyond the standard PostgreSQL
 * full-text search capabilities (tsvector, tsquery, etc.).
 */
class PgKeywordIndexSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  // Skip tests if no PostgreSQL available
  private val testUrl   = sys.env.get("PGVECTOR_TEST_URL")
  private val skipTests = testUrl.isEmpty

  private var index: PgKeywordIndex = _
  private val testTableName         = s"test_keyword_${System.currentTimeMillis()}"

  override def beforeEach(): Unit =
    if (!skipTests) {
      index = PgKeywordIndex(
        testUrl.get,
        sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres"),
        sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""),
        testTableName
      ).fold(
        e => fail(s"Failed to create index: ${e.formatted}"),
        identity
      )
    }

  override def afterEach(): Unit =
    if (index != null) {
      // Clean up test table
      Try {
        index.clear()
      }
      index.close()
    }

  private def skipIfNoPg(test: => Unit): Unit =
    if (skipTests) {
      info("Skipping test - PGVECTOR_TEST_URL not set")
    } else {
      test
    }

  "PgKeywordIndex" should {

    "index and retrieve a single document" in skipIfNoPg {
      val doc = KeywordDocument(
        id = "doc-1",
        content = "PostgreSQL is a powerful open source database",
        metadata = Map("source" -> "test", "type" -> "tutorial")
      )

      index.index(doc) shouldBe Right(())

      val retrieved = index.get("doc-1")
      retrieved.isRight shouldBe true
      retrieved.toOption.flatten.map(_.id) shouldBe Some("doc-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some("PostgreSQL is a powerful open source database")
      retrieved.toOption.flatten.map(_.metadata) shouldBe Some(Map("source" -> "test", "type" -> "tutorial"))
    }

    "return None for non-existent document" in skipIfNoPg {
      val result = index.get("non-existent")
      result shouldBe Right(None)
    }

    "upsert (replace) existing document" in skipIfNoPg {
      val doc1 = KeywordDocument("doc-1", "Original content")
      val doc2 = KeywordDocument("doc-1", "Updated content")

      index.index(doc1) shouldBe Right(())
      index.index(doc2) shouldBe Right(())

      val retrieved = index.get("doc-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some("Updated content")
    }

    "index multiple documents in batch" in skipIfNoPg {
      val docs = (1 to 10).map(i => KeywordDocument(s"batch-$i", s"Content for document number $i"))

      index.indexBatch(docs) shouldBe Right(())

      val count = index.count()
      count shouldBe Right(10L)
    }

    "search for matching documents" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument(
          "scala-1",
          "Scala is a programming language that combines object-oriented and functional programming"
        ),
        KeywordDocument("java-1", "Java is a widely-used programming language designed for portability"),
        KeywordDocument("python-1", "Python is a high-level programming language known for readability"),
        KeywordDocument("scala-2", "Scala programming runs on the JVM and is compatible with Java libraries")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("scala programming", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get

      // Should find Scala documents
      found.map(_.id) should contain("scala-1")
      found.map(_.id) should contain("scala-2")
    }

    "rank results by relevance" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("exact", "database database database database"), // High term frequency
        KeywordDocument("partial", "database performance optimization"),
        KeywordDocument("unrelated", "cooking recipes and food preparation")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("database", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get

      // Should rank "exact" highest due to term frequency
      found.size should be >= 1
      found.head.id shouldBe "exact"
      found.head.score should be > found(1).score
    }

    "search with phrase matching using quotes" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("phrase-match", "The quick brown fox jumps over the lazy dog"),
        KeywordDocument("partial-match", "The fox is quick and brown"),
        KeywordDocument("no-match", "Cats are wonderful pets")
      )
      index.indexBatch(docs) shouldBe Right(())

      // websearch_to_tsquery handles quoted phrases
      val results = index.search("\"quick brown fox\"", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get

      // Only the exact phrase match should appear
      found.size should be >= 1
      found.head.id shouldBe "phrase-match"
    }

    "search with OR operator" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("scala-doc", "Scala is a functional programming language"),
        KeywordDocument("java-doc", "Java is an object-oriented language"),
        KeywordDocument("rust-doc", "Rust focuses on memory safety")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("scala OR java", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get

      found.size shouldBe 2
      found.map(_.id).toSet shouldBe Set("scala-doc", "java-doc")
    }

    "search with highlighted snippets" in skipIfNoPg {
      val doc = KeywordDocument(
        "highlight-test",
        "PostgreSQL provides powerful full-text search capabilities with tsvector and tsquery"
      )
      index.index(doc) shouldBe Right(())

      val results = index.searchWithHighlights("postgresql search", topK = 10, snippetLength = 50)
      results.isRight shouldBe true
      val found = results.toOption.get

      found.size shouldBe 1
      found.head.highlights should not be empty
      // Highlights should contain <b> tags
      found.head.highlights.head should include("<b>")
    }

    "filter results by metadata" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("en-1", "Hello world", Map("lang" -> "en")),
        KeywordDocument("en-2", "Hello universe", Map("lang" -> "en")),
        KeywordDocument("es-1", "Hola mundo", Map("lang" -> "es"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val filter  = Some(MetadataFilter.Equals("lang", "en"))
      val results = index.search("hello", topK = 10, filter = filter)
      results.isRight shouldBe true

      val found = results.toOption.get
      found.size shouldBe 2
      found.map(_.id).toSet shouldBe Set("en-1", "en-2")
    }

    "delete a document" in skipIfNoPg {
      val doc = KeywordDocument("delete-me", "Content to delete")
      index.index(doc) shouldBe Right(())

      index.delete("delete-me") shouldBe Right(())

      index.get("delete-me") shouldBe Right(None)
    }

    "delete multiple documents in batch" in skipIfNoPg {
      val docs = (1 to 5).map(i => KeywordDocument(s"del-$i", s"Content $i"))
      index.indexBatch(docs) shouldBe Right(())

      index.deleteBatch(Seq("del-1", "del-2", "del-3")) shouldBe Right(())

      index.count() shouldBe Right(2L)
      index.get("del-4").toOption.flatten shouldBe defined
      index.get("del-5").toOption.flatten shouldBe defined
    }

    "delete documents by ID prefix" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("prefix-a-1", "Content A1"),
        KeywordDocument("prefix-a-2", "Content A2"),
        KeywordDocument("prefix-b-1", "Content B1")
      )
      index.indexBatch(docs) shouldBe Right(())

      val deleted = index.deleteByPrefix("prefix-a")
      deleted shouldBe Right(2L)

      index.count() shouldBe Right(1L)
      index.get("prefix-b-1").toOption.flatten shouldBe defined
    }

    "clear all documents" in skipIfNoPg {
      val docs = (1 to 5).map(i => KeywordDocument(s"clear-$i", s"Content $i"))
      index.indexBatch(docs) shouldBe Right(())

      index.clear() shouldBe Right(())

      index.count() shouldBe Right(0L)
    }

    "return correct statistics" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("s1", "one two three"),
        KeywordDocument("s2", "four five six seven"),
        KeywordDocument("s3", "eight nine")
      )
      index.indexBatch(docs) shouldBe Right(())

      val stats = index.stats()
      stats.isRight shouldBe true
      stats.toOption.get.totalDocuments shouldBe 3
      stats.toOption.get.totalTokens shouldBe defined
      stats.toOption.get.totalTokens.get should be >= 9L
      stats.toOption.get.avgDocumentLength shouldBe defined
      stats.toOption.get.indexSizeBytes shouldBe defined
    }

    "handle empty search query gracefully" in skipIfNoPg {
      val doc = KeywordDocument("doc-1", "Some content")
      index.index(doc) shouldBe Right(())

      val results = index.search("", topK = 10)
      results.isRight shouldBe true
      results.toOption.get shouldBe empty
    }

    "handle empty batch operations" in skipIfNoPg {
      index.indexBatch(Seq.empty) shouldBe Right(())
      index.deleteBatch(Seq.empty) shouldBe Right(())
    }

    "combine metadata filters with AND" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("r1", "content", Map("a" -> "1", "b" -> "x")),
        KeywordDocument("r2", "content", Map("a" -> "1", "b" -> "y")),
        KeywordDocument("r3", "content", Map("a" -> "2", "b" -> "x"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val filter  = MetadataFilter.Equals("a", "1").and(MetadataFilter.Equals("b", "x"))
      val results = index.search("content", topK = 10, filter = Some(filter))

      results.toOption.get.size shouldBe 1
      results.toOption.get.head.id shouldBe "r1"
    }

    "combine metadata filters with OR" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("r1", "content", Map("cat" -> "a")),
        KeywordDocument("r2", "content", Map("cat" -> "b")),
        KeywordDocument("r3", "content", Map("cat" -> "c"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val filter  = MetadataFilter.Equals("cat", "a").or(MetadataFilter.Equals("cat", "b"))
      val results = index.search("content", topK = 10, filter = Some(filter))

      results.toOption.get.size shouldBe 2
      results.toOption.get.map(_.id).toSet shouldBe Set("r1", "r2")
    }

    "handle special characters in content" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("special-1", "C++ is a programming language"),
        KeywordDocument("special-2", "user@example.com is an email"),
        KeywordDocument("special-3", "Price is $99.99")
      )
      index.indexBatch(docs) shouldBe Right(())

      // These should at least not throw errors
      val results = index.search("programming", topK = 10)
      results.isRight shouldBe true
    }

    "handle metadata with special characters" in skipIfNoPg {
      val doc = KeywordDocument(
        "meta-special",
        "Content",
        Map("key with spaces" -> "value with \"quotes\"")
      )
      index.index(doc) shouldBe Right(())

      val retrieved = index.get("meta-special")
      retrieved.isRight shouldBe true
      retrieved.toOption.flatten.map(_.metadata) shouldBe Some(Map("key with spaces" -> "value with \"quotes\""))
    }
  }

  "PgKeywordIndex factory" should {

    "reject invalid language from config before datasource creation" in {
      val config = PgKeywordIndex.Config(
        host = "invalid-host",
        port = 5432,
        database = "invalid-db",
        user = "user",
        password = "pass",
        tableName = "safe_table",
        language = "english'); DROP TABLE users; --"
      )

      PgKeywordIndex(config) should matchPattern {
        case Left(ProcessingError(msg, "pg-keyword-index", _)) if msg.contains("Invalid language") =>
      }
    }

    "reject invalid language when using existing datasource factory" in {
      val result = PgKeywordIndex(null, "safe_table", "english'); DROP TABLE users; --")

      result should matchPattern {
        case Left(ProcessingError(msg, "pg-keyword-index", _)) if msg.contains("Invalid language") =>
      }
    }

    "create index from config" in skipIfNoPg {
      // Parse connection details from JDBC URL
      // Format: jdbc:postgresql://host:port/database
      val urlPattern = """jdbc:postgresql://([^:]+):(\d+)/(.+)""".r
      val (host, port, database) = testUrl.get match {
        case urlPattern(h, p, d) => (h, p.toInt, d)
        case _                   => ("localhost", 5432, "postgres")
      }

      val config = PgKeywordIndex.Config(
        host = host,
        port = port,
        database = database,
        user = sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres"),
        password = sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""),
        tableName = s"test_factory_${System.currentTimeMillis()}"
      )

      val result = PgKeywordIndex(config)
      result.isRight shouldBe true
      result.toOption.get.close()
    }

    "create index from JDBC URL" in skipIfNoPg {
      val newTableName = s"test_jdbc_${System.currentTimeMillis()}"
      val result = PgKeywordIndex(
        testUrl.get,
        sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres"),
        sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""),
        newTableName
      )

      result.isRight shouldBe true
      result.toOption.get.close()
    }
  }
}
