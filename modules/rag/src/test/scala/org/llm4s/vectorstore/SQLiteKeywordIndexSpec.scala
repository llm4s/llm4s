package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.EitherValues

/**
 * Comprehensive tests for SQLiteKeywordIndex BM25 scoring.
 *
 * Tests the BM25 ranking algorithm's key characteristics:
 * - Term frequency (TF): More occurrences → higher score
 * - Inverse document frequency (IDF): Rare terms → higher score
 * - Document length normalization: Penalizes long documents
 * - Multi-term queries: Combines scores appropriately
 */
class SQLiteKeywordIndexSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach with EitherValues {

  private var index: KeywordIndex = _

  override def beforeEach(): Unit =
    index = SQLiteKeywordIndex
      .inMemory()
      .fold(
        e => fail(s"Failed to create index: ${e.formatted}"),
        identity
      )

  override def afterEach(): Unit =
    if (index != null) {
      index.close()
    }

  behavior.of("SQLiteKeywordIndex BM25 scoring")

  it should "correctly rank results using BM25 scoring with term frequency" in {
    // Arrange: Create documents with varying term frequencies
    // The term "scala" appears with different frequencies
    val docs = Seq(
      KeywordDocument(
        "doc-high-tf",
        "Scala Scala Scala Scala Scala programming language" // 5 occurrences
      ),
      KeywordDocument(
        "doc-medium-tf",
        "Scala Scala programming language" // 2 occurrences
      ),
      KeywordDocument(
        "doc-low-tf",
        "Scala programming language" // 1 occurrence
      )
    )
    index.indexBatch(docs).value

    // Act: Search for "scala"
    val results = index.search("scala", topK = 10).value

    // Assert: Documents should be ranked by term frequency (more occurrences = higher score)
    results should have size 3
    results.map(_.id) shouldBe Seq("doc-high-tf", "doc-medium-tf", "doc-low-tf")

    // Verify scores are monotonically decreasing
    results(0).score should be > results(1).score
    results(1).score should be > results(2).score

    // Verify all scores are positive (SQLite FTS5 returns negated BM25, then we negate again)
    results.foreach(_.score should be > 0.0)
  }

  it should "correctly handle inverse document frequency (rare terms score higher)" in {
    // Arrange: Create corpus where "rare" appears once, "common" appears in all docs
    val docs = Seq(
      KeywordDocument("doc1", "This document contains a rare term and a common word"),
      KeywordDocument("doc2", "This document only has common word"),
      KeywordDocument("doc3", "Another document with common word"),
      KeywordDocument("doc4", "Yet another with common word")
    )
    index.indexBatch(docs).value

    // Act: Search for rare term
    val rareResults = index.search("rare", topK = 10).value

    // Act: Search for common term
    val commonResults = index.search("common", topK = 10).value

    // Assert: Rare term should have higher score in its single document
    // than common term in any document
    rareResults should have size 1
    commonResults should have size 4

    // The rare term's score in its document should be notably higher
    // than the common term's average score
    val rareScore      = rareResults.head.score
    val avgCommonScore = commonResults.map(_.score).sum / commonResults.size

    rareScore should be > avgCommonScore
  }

  it should "apply document length normalization" in {
    // Arrange: Create short and long documents with same term frequency
    val docs = Seq(
      KeywordDocument(
        "short",
        "Scala programming" // Short document, 2 words
      ),
      KeywordDocument(
        "long",
        // Long document with same "scala" frequency but much longer
        "Scala is a programming language that combines object oriented and functional " +
          "programming paradigms on the Java Virtual Machine platform with excellent " +
          "type safety interoperability performance and developer productivity"
      )
    )
    index.indexBatch(docs).value

    // Act: Search for "scala"
    val results = index.search("scala", topK = 10).value

    // Assert: Short document should rank higher due to length normalization
    // BM25 penalizes longer documents to prevent them dominating by sheer size
    results should have size 2
    results.head.id shouldBe "short"
    results.head.score should be > results(1).score
  }

  it should "correctly combine scores for multi-term queries" in {
    // Arrange: Create documents matching different numbers of query terms
    // Note: SQLite FTS5 by default uses AND logic for space-separated terms
    val docs = Seq(
      KeywordDocument("both", "Scala functional programming language"),       // matches both
      KeywordDocument("both-repeated", "Scala Scala functional programming"), // matches both with higher TF
      KeywordDocument("scala-only", "Scala is object-oriented"),              // matches "scala" only
      KeywordDocument("functional-only", "Haskell is functional"),            // matches "functional" only
      KeywordDocument("neither", "Python uses dynamic typing")                // matches neither
    )
    index.indexBatch(docs).value

    // Act: Search for two terms. Every term is optional (#2995): a document with either matches
    val results = index.search("scala functional", topK = 10).value

    // Assert: every document holding a term matches, those holding both ranked first
    results.map(_.id).toSet shouldBe Set("both", "both-repeated", "scala-only", "functional-only")
    results.take(2).map(_.id).toSet shouldBe Set("both", "both-repeated")

    // Document with more occurrences should rank higher
    results.head.id shouldBe "both-repeated"
    results.head.score should be > results(1).score
  }

  it should "handle queries with varying term frequencies across documents" in {
    // Arrange: More realistic scenario with mixed frequencies
    val docs = Seq(
      KeywordDocument(
        "comprehensive",
        "Scala programming Scala language Scala design Scala implementation" // 4x "scala"
      ),
      KeywordDocument(
        "balanced",
        "Scala is a modern programming language for the JVM" // 1x "scala", 1x "programming"
      ),
      KeywordDocument(
        "programming-heavy",
        "Programming with Scala means functional programming and OOP programming" // 4x "programming", 1x "scala"
      )
    )
    index.indexBatch(docs).value

    // Act: Search for "scala"
    val scalaResults = index.search("scala", topK = 10).value

    // Act: Search for "programming"
    val progResults = index.search("programming", topK = 10).value

    // Assert: For "scala" query, doc with most "scala" occurrences ranks first
    scalaResults.head.id shouldBe "comprehensive"

    // Assert: For "programming" query, doc with most "programming" occurrences ranks first
    progResults.head.id shouldBe "programming-heavy"

    // Assert: All results have positive scores
    (scalaResults ++ progResults).foreach(_.score should be > 0.0)
  }

  it should "return empty results for non-matching queries" in {
    // Arrange
    val docs = Seq(
      KeywordDocument("doc1", "Scala programming language"),
      KeywordDocument("doc2", "Functional programming paradigm")
    )
    index.indexBatch(docs).value

    // Act
    val results = index.search("python", topK = 10).value

    // Assert
    results shouldBe empty
  }

  it should "respect topK limit in BM25 ranked results" in {
    // Arrange: Create many matching documents
    val docs = (1 to 20).map { i =>
      KeywordDocument(
        s"doc-$i",
        s"Scala programming ${"language " * i}" // Varying length/frequency
      )
    }
    index.indexBatch(docs).value

    // Act: Request only top 5
    val results = index.search("scala", topK = 5).value

    // Assert
    results should have size 5

    // Verify results are properly ranked (descending scores)
    results.sliding(2).foreach { case Seq(a, b) =>
      a.score should be >= b.score
    }
  }

  it should "handle empty index gracefully" in {
    // Arrange: Empty index (no documents)

    // Act
    val results = index.search("anything", topK = 10).value

    // Assert
    results shouldBe empty
  }

  it should "handle single document index" in {
    // Arrange
    val doc = KeywordDocument("lonely", "Scala programming language")
    index.index(doc).value

    // Act
    val results = index.search("scala", topK = 10).value

    // Assert
    results should have size 1
    results.head.id shouldBe "lonely"
    results.head.score should be > 0.0
  }

  it should "maintain score consistency across identical queries" in {
    // Arrange
    val docs = Seq(
      KeywordDocument("doc1", "Scala functional programming"),
      KeywordDocument("doc2", "Scala object oriented programming"),
      KeywordDocument("doc3", "Scala type safe programming")
    )
    index.indexBatch(docs).value

    // Act: Run same query multiple times
    val results1 = index.search("scala programming", topK = 10).value
    val results2 = index.search("scala programming", topK = 10).value
    val results3 = index.search("scala programming", topK = 10).value

    // Assert: Results should be identical
    results1.map(r => (r.id, r.score)) shouldBe results2.map(r => (r.id, r.score))
    results2.map(r => (r.id, r.score)) shouldBe results3.map(r => (r.id, r.score))
  }

  "SQLiteKeywordIndex.matchExpression" should "quote every term and join them with OR (#2995)" in {
    import SQLiteKeywordIndex.matchExpression
    matchExpression("what is the capital") shouldBe Some("\"what\" OR \"is\" OR \"the\" OR \"capital\"")
    matchExpression("scala AND java OR rust") shouldBe Some("\"scala\" OR \"java\" OR \"rust\"")
    matchExpression("NOT scala") shouldBe Some("\"NOT\" OR \"scala\"")
  }

  it should "keep a balanced phrase, read an unpaired quote as text, and honour a trailing *" in {
    import SQLiteKeywordIndex.matchExpression
    matchExpression("\"hello world\" again") shouldBe Some("\"hello world\" OR \"again\"")
    matchExpression("he said \"hi") shouldBe Some("\"he\" OR \"said\" OR \"hi\"")
    matchExpression("scala*") shouldBe Some("\"scala\"*")
  }

  it should "find nothing to search in a blank query or one of only punctuation" in {
    import SQLiteKeywordIndex.matchExpression
    Seq("", "   ", "\"", "\"\"", "* ^ ( )").foreach(q => withClue(q)(matchExpression(q) shouldBe None))
    matchExpression(null) shouldBe None
  }

  "SQLiteKeywordIndex" should "rebuild, when opened, an index an earlier version left with stale FTS rows (#2994)" in {
    val dir  = java.nio.file.Files.createTempDirectory("llm4s-kw-stale")
    val path = dir.resolve("kw.db").toString
    SQLiteKeywordIndex(path).value.close()
    // The state INSERT OR REPLACE left: d1's document is the new text, and the FTS table also holds the old row
    scala.util.Using.resource(java.sql.DriverManager.getConnection(s"jdbc:sqlite:$path")) { conn =>
      scala.util.Using.resource(conn.createStatement()) { stmt =>
        stmt.executeUpdate("INSERT INTO documents (id, content, metadata) VALUES ('d1', 'delta epsilon', '{}')")
        stmt.executeUpdate("INSERT INTO documents_fts (id, content) VALUES ('d1', 'alpha beta')")
      }
    }

    val reopened = SQLiteKeywordIndex(path).value
    reopened.search("alpha", 5).value shouldBe empty
    reopened.search("delta", 5).value.map(_.id) shouldBe Seq("d1")
    reopened.close()
    val again = SQLiteKeywordIndex(path).value // idempotent
    again.search("delta", 5).value.map(_.id) shouldBe Seq("d1")
    again.close()
  }

  it should "take the write lock before its stale check, so a concurrent write is indexed once" in {
    import scala.concurrent.{ Await, Future }
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration.*

    val dir  = java.nio.file.Files.createTempDirectory("llm4s-kw-race")
    val path = dir.resolve("kw.db").toString
    SQLiteKeywordIndex(path).value.close()
    def fts(conn: java.sql.Connection): Long =
      scala.util.Using.resource(conn.createStatement())(s =>
        scala.util.Using.resource(s.executeQuery("SELECT COUNT(*) FROM documents_fts")) { rs =>
          rs.next(); rs.getLong(1)
        }
      )

    scala.util.Using.resource(java.sql.DriverManager.getConnection(s"jdbc:sqlite:$path")) { writer =>
      scala.util.Using.resource(writer.createStatement()) { stmt =>
        // stale: a document whose old version also has an FTS row
        stmt.executeUpdate("INSERT INTO documents (id, content, metadata) VALUES ('d1', 'delta', '{}')")
        stmt.executeUpdate("INSERT INTO documents_fts (id, content) VALUES ('d1', 'alpha')")
        // another writer holds the write lock while the index opens
        stmt.execute("BEGIN IMMEDIATE")
        val opening = Future(SQLiteKeywordIndex(path).value)
        Thread.sleep(300)
        opening.isCompleted shouldBe false // waiting for the lock, before its check
        stmt.executeUpdate("INSERT INTO documents (id, content, metadata) VALUES ('d2', 'gamma', '{}')")
        stmt.execute("COMMIT")
        val index = Await.result(opening, 10.seconds)
        fts(writer) shouldBe 2L // d1 and d2, each once
        index.search("alpha", 5).value shouldBe empty
        index.search("gamma", 5).value.map(_.id) shouldBe Seq("d2")
        index.close()
      }
    }
  }
}
