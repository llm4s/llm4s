package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach

/**
 * Tests for KeywordIndex and SQLiteKeywordIndex.
 *
 * Tests BM25-based keyword search using SQLite FTS5.
 */
class KeywordIndexSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  private var index: KeywordIndex = _

  override def beforeEach(): Unit =
    index = KeywordIndex
      .inMemory()
      .fold(
        e => fail(s"Failed to create index: ${e.formatted}"),
        identity
      )

  override def afterEach(): Unit =
    if (index != null) {
      index.close()
    }

  "KeywordIndex" should {

    "index and retrieve a single document" in {
      val doc = KeywordDocument(
        id = "doc-1",
        content = "Scala is a powerful programming language",
        metadata = Map("type" -> "article", "lang" -> "en")
      )

      index.index(doc) shouldBe Right(())

      val retrieved = index.get("doc-1")
      retrieved.isRight shouldBe true
      retrieved.toOption.flatten.map(_.id) shouldBe Some("doc-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some("Scala is a powerful programming language")
      retrieved.toOption.flatten.map(_.metadata) shouldBe Some(Map("type" -> "article", "lang" -> "en"))
    }

    "return None for non-existent document" in {
      val result = index.get("non-existent")
      result shouldBe Right(None)
    }

    "index multiple documents in batch" in {
      val docs = (1 to 10).map(i => KeywordDocument(s"batch-$i", s"Document number $i with content"))

      index.indexBatch(docs) shouldBe Right(())
      index.count() shouldBe Right(10L)
    }

    "update existing document" in {
      val doc1 = KeywordDocument("update-me", "Original content")
      val doc2 = KeywordDocument("update-me", "Updated content")

      index.index(doc1) shouldBe Right(())
      index.update(doc2) shouldBe Right(())

      val retrieved = index.get("update-me")
      retrieved.toOption.flatten.map(_.content) shouldBe Some("Updated content")
    }

    "delete a document" in {
      val doc = KeywordDocument("delete-me", "Content to delete")
      index.index(doc) shouldBe Right(())

      index.delete("delete-me") shouldBe Right(())
      index.get("delete-me") shouldBe Right(None)
    }

    "delete multiple documents" in {
      val docs = (1 to 5).map(i => KeywordDocument(s"del-$i", s"Content $i"))
      index.indexBatch(docs) shouldBe Right(())

      index.deleteBatch(Seq("del-1", "del-2", "del-3")) shouldBe Right(())

      index.count() shouldBe Right(2L)
      index.get("del-4").toOption.flatten shouldBe defined
      index.get("del-5").toOption.flatten shouldBe defined
    }

    "clear all documents" in {
      val docs = (1 to 5).map(i => KeywordDocument(s"clear-$i", s"Content $i"))
      index.indexBatch(docs) shouldBe Right(())

      index.clear() shouldBe Right(())
      index.count() shouldBe Right(0L)
    }

    "search for matching documents" in {
      val docs = Seq(
        KeywordDocument("scala-1", "Scala is a functional and object-oriented language"),
        KeywordDocument("scala-2", "Scala runs on the JVM and interops with Java"),
        KeywordDocument("python-1", "Python is a dynamically typed language"),
        KeywordDocument("java-1", "Java is an object-oriented language for the JVM")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("Scala", topK = 10)
      results.isRight shouldBe true

      val matches = results.toOption.get
      matches.size shouldBe 2
      matches.map(_.id).toSet shouldBe Set("scala-1", "scala-2")
    }

    "rank results by relevance (BM25)" in {
      val docs = Seq(
        KeywordDocument("high-match", "Scala Scala Scala programming Scala"),
        KeywordDocument("medium-match", "Scala is a great programming language"),
        KeywordDocument("low-match", "Programming languages include Scala")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("Scala", topK = 10)
      results.isRight shouldBe true

      val matches = results.toOption.get
      matches.size shouldBe 3

      // Higher term frequency should rank higher
      matches.head.id shouldBe "high-match"
      matches.head.score should be > matches(1).score
    }

    "search with phrase matching" in {
      val docs = Seq(
        KeywordDocument("phrase-match", "Learn Scala programming from scratch"),
        KeywordDocument("no-phrase", "Scala is great for programming")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("\"Scala programming\"", topK = 10)
      results.isRight shouldBe true

      val matches = results.toOption.get
      matches.size shouldBe 1
      matches.head.id shouldBe "phrase-match"
    }

    "search with highlighted snippets" in {
      val docs = Seq(
        KeywordDocument("highlight-1", "Scala is a powerful programming language for building scalable applications")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.searchWithHighlights("Scala", topK = 10, snippetLength = 50)
      results.isRight shouldBe true

      val matches = results.toOption.get
      matches.size shouldBe 1
      matches.head.highlights.nonEmpty shouldBe true
      matches.head.highlights.head should include("<b>")
    }

    "filter results by metadata" in {
      val docs = Seq(
        KeywordDocument("en-1", "Scala programming guide", Map("lang" -> "en", "type" -> "guide")),
        KeywordDocument("en-2", "Scala tutorial", Map("lang" -> "en", "type" -> "tutorial")),
        KeywordDocument("es-1", "Scala guia de programacion", Map("lang" -> "es", "type" -> "guide"))
      )
      index.indexBatch(docs) shouldBe Right(())

      // Filter by language
      val langFilter = MetadataFilter.Equals("lang", "en")
      val enResults  = index.search("Scala", topK = 10, filter = Some(langFilter))
      enResults.toOption.get.size shouldBe 2
      enResults.toOption.get.map(_.id).toSet shouldBe Set("en-1", "en-2")

      // Filter by type
      val typeFilter   = MetadataFilter.Equals("type", "guide")
      val guideResults = index.search("Scala", topK = 10, filter = Some(typeFilter))
      guideResults.toOption.get.size shouldBe 2
      guideResults.toOption.get.map(_.id).toSet shouldBe Set("en-1", "es-1")
    }

    "combine filters with AND/OR" in {
      val docs = Seq(
        KeywordDocument("r1", "Scala content A", Map("a" -> "1", "b" -> "x")),
        KeywordDocument("r2", "Scala content B", Map("a" -> "1", "b" -> "y")),
        KeywordDocument("r3", "Scala content C", Map("a" -> "2", "b" -> "x"))
      )
      index.indexBatch(docs) shouldBe Right(())

      // AND filter
      val andFilter  = MetadataFilter.Equals("a", "1").and(MetadataFilter.Equals("b", "x"))
      val andResults = index.search("Scala", topK = 10, filter = Some(andFilter))
      andResults.toOption.get.size shouldBe 1
      andResults.toOption.get.head.id shouldBe "r1"

      // OR filter
      val orFilter  = MetadataFilter.Equals("a", "2").or(MetadataFilter.Equals("b", "y"))
      val orResults = index.search("Scala", topK = 10, filter = Some(orFilter))
      orResults.toOption.get.size shouldBe 2
    }

    "reject unsafe metadata keys in filters" in {
      val docs = Seq(
        KeywordDocument("doc-1", "Scala content", Map("lang" -> "en"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val unsafeKey = "lang') OR 1=1 --"
      val result    = index.search("Scala", topK = 10, filter = Some(MetadataFilter.Equals(unsafeKey, "en")))

      result.isLeft shouldBe true
      result.left.toOption.get.formatted should include("Invalid metadata key")
    }

    "propagate invalid metadata keys through composite filters" in {
      val docs = Seq(
        KeywordDocument("doc-1", "Scala content", Map("lang" -> "en"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val unsafeKey = "lang') OR 1=1 --"
      val composite = MetadataFilter.And(
        MetadataFilter.Equals("lang", "en"),
        MetadataFilter.Or(
          MetadataFilter.HasKey(unsafeKey),
          MetadataFilter.Not(MetadataFilter.Contains("lang", "fr"))
        )
      )

      val result = index.search("Scala", topK = 10, filter = Some(composite))
      result.isLeft shouldBe true
      result.left.toOption.get.formatted should include("Invalid metadata key")
    }

    "reject empty metadata keys in filters" in {
      val docs = Seq(
        KeywordDocument("doc-1", "Scala content", Map("lang" -> "en"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val result = index.search("Scala", topK = 10, filter = Some(MetadataFilter.Equals("   ", "en")))

      result.isLeft shouldBe true
      result.left.toOption.get.formatted should include("Invalid metadata key")
    }

    "allow metadata keys that begin with digits" in {
      val docs = Seq(
        KeywordDocument("doc-2024", "Scala release notes", Map("2024" -> "yes"))
      )
      index.indexBatch(docs) shouldBe Right(())

      val result = index.search("Scala", topK = 10, filter = Some(MetadataFilter.Equals("2024", "yes")))

      result.isRight shouldBe true
      result.toOption.get.map(_.id).toSet shouldBe Set("doc-2024")
    }

    "return correct statistics" in {
      val docs = Seq(
        KeywordDocument("s1", "Short content"),
        KeywordDocument("s2", "Longer content with more words here"),
        KeywordDocument("s3", "Medium content here")
      )
      index.indexBatch(docs) shouldBe Right(())

      val stats = index.stats()
      stats.isRight shouldBe true
      stats.toOption.get.totalDocuments shouldBe 3
      stats.toOption.get.totalTokens shouldBe defined
      stats.toOption.get.avgDocumentLength shouldBe defined
    }

    "handle empty queries gracefully" in {
      val docs = Seq(KeywordDocument("doc-1", "Some content here"))
      index.indexBatch(docs) shouldBe Right(())

      // Empty query returns an error in FTS5 (invalid query syntax)
      val results = index.search("", topK = 10)
      // Either no results or an error is acceptable for empty query
      results.isLeft || results.toOption.get.isEmpty shouldBe true
    }

    "handle special characters in content" in {
      val docs = Seq(
        KeywordDocument("special-1", "User's guide to Scala (v3.0)"),
        KeywordDocument("special-2", "Email: test@example.com"),
        KeywordDocument("special-3", "Price: $99.99")
      )
      index.indexBatch(docs) shouldBe Right(())

      val results = index.search("guide", topK = 10)
      results.isRight shouldBe true
      results.toOption.get.size shouldBe 1
    }
  }

  "SQLiteKeywordIndex factory" should {

    "create index from config" in {
      val config = SQLiteKeywordIndex.Config.inMemory.withTableName("test_docs")
      val result = SQLiteKeywordIndex(config)

      result.isRight shouldBe true
      result.toOption.get.close()
    }

    "reject invalid table names" in {
      val invalidName = "docs; DROP TABLE users; --"
      val result      = SQLiteKeywordIndex.inMemory(invalidName)

      result.isLeft shouldBe true
      result.left.toOption.get.formatted should include("Invalid table name")
    }

    "reject empty table names" in {
      val result = SQLiteKeywordIndex.inMemory("   ")

      result.isLeft shouldBe true
      result.left.toOption.get.formatted should include("Table name must not be empty")
    }

    "allow SQLite-compatible table names longer than 63 characters" in {
      val longTableName = "docs_" + ("x" * 80)
      val result        = SQLiteKeywordIndex.inMemory(longTableName)

      result.isRight shouldBe true
      result.toOption.get.close()
    }

    "support file-based persistence" in {
      val tempFile = java.io.File.createTempFile("keyword-test", ".db")
      tempFile.deleteOnExit()

      // Create and populate index
      val index1 = SQLiteKeywordIndex(tempFile.getPath).toOption.get
      index1.index(KeywordDocument("persist-1", "Persistent content")) shouldBe Right(())
      index1.close()

      // Reopen and verify persistence
      val index2 = SQLiteKeywordIndex(tempFile.getPath).toOption.get
      val doc    = index2.get("persist-1")
      doc.toOption.flatten shouldBe defined
      doc.toOption.flatten.get.content shouldBe "Persistent content"
      index2.close()
    }
  }
}
