package org.llm4s.vectorstore

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
 *
 * To run locally:
 *   sbt "it/testOnly org.llm4s.vectorstore.PgKeywordIndexSpec"
 */
class PgKeywordIndexSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

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
      Try {
        index.clear()
      }
      index.close()
    }

  private def skipIfNoPg(test: => Unit): Unit =
    if (skipTests) info("Skipping test - PGVECTOR_TEST_URL not set")
    else test

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
        KeywordDocument("scala-1", "Scala is a programming language that combines object-oriented and functional programming"),
        KeywordDocument("java-1", "Java is a widely-used programming language designed for portability"),
        KeywordDocument("python-1", "Python is a high-level programming language known for readability"),
        KeywordDocument("scala-2", "Scala programming runs on the JVM and is compatible with Java libraries")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("scala programming", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get
      found.map(_.id) should contain("scala-1")
      found.map(_.id) should contain("scala-2")
    }

    "rank results by relevance" in skipIfNoPg {
      val docs = Seq(
        KeywordDocument("exact", "database database database database"),
        KeywordDocument("partial", "database performance optimization"),
        KeywordDocument("unrelated", "cooking recipes and food preparation")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("database", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get
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

      val results = index.search("\"quick brown fox\"", topK = 10)
      results.isRight shouldBe true
      val found = results.toOption.get
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
  }
}
