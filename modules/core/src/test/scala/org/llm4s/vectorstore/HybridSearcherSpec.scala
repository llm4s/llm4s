package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach

/**
 * Tests for HybridSearcher combining vector and keyword search.
 */
class HybridSearcherSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  private var vectorStore: VectorStore   = _
  private var keywordIndex: KeywordIndex = _
  private var searcher: HybridSearcher   = _

  // Sample documents with embeddings
  private val docs = Seq(
    ("scala-guide", "Scala is a powerful functional programming language", Array(0.9f, 0.1f, 0.0f)),
    ("scala-jvm", "Scala runs on the JVM and interoperates with Java", Array(0.8f, 0.2f, 0.1f)),
    ("python-intro", "Python is a dynamically typed programming language", Array(0.2f, 0.9f, 0.0f)),
    ("java-basics", "Java is an object-oriented programming language", Array(0.3f, 0.8f, 0.2f)),
    ("rust-systems", "Rust is a systems programming language focused on safety", Array(0.1f, 0.3f, 0.9f))
  )

  override def beforeEach(): Unit = {
    vectorStore = VectorStoreFactory.inMemory().fold(e => fail(s"Failed: ${e.formatted}"), identity)
    keywordIndex = KeywordIndex.inMemory().fold(e => fail(s"Failed: ${e.formatted}"), identity)
    searcher = HybridSearcher(vectorStore, keywordIndex)

    // Populate both stores
    docs.foreach { case (id, content, embedding) =>
      vectorStore.upsert(VectorRecord(id, embedding, Some(content), Map("type" -> "doc")))
      keywordIndex.index(KeywordDocument(id, content, Map("type" -> "doc")))
    }
  }

  override def afterEach(): Unit =
    if (searcher != null) {
      searcher.close()
    }

  "HybridSearcher" should {

    "perform vector-only search" in {
      // Query embedding similar to Scala docs
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val results = searcher.search(
        queryEmbedding,
        "ignored for vector-only",
        topK = 3,
        strategy = FusionStrategy.VectorOnly
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      matches.size shouldBe 3

      // Scala docs should rank highest (similar embedding)
      matches.head.id should startWith("scala")
      matches.head.vectorScore shouldBe defined
      matches.head.keywordScore shouldBe None
    }

    "perform keyword-only search" in {
      val queryEmbedding = Array(0.0f, 0.0f, 0.0f) // Ignored

      val results = searcher.search(
        queryEmbedding,
        "Scala programming",
        topK = 3,
        strategy = FusionStrategy.KeywordOnly
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      matches.nonEmpty shouldBe true

      // Results should have keyword scores
      matches.head.keywordScore shouldBe defined
      matches.head.vectorScore shouldBe None
    }

    "perform hybrid search with RRF fusion" in {
      // Query similar to Scala docs
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)
      val queryText      = "Scala functional programming"

      val results = searcher.search(
        queryEmbedding,
        queryText,
        topK = 3,
        strategy = FusionStrategy.RRF(k = 60)
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      matches.size shouldBe 3

      // Scala guide should rank high (matches both vector and keyword)
      val scalaGuide = matches.find(_.id == "scala-guide")
      scalaGuide shouldBe defined
      scalaGuide.get.vectorScore shouldBe defined
      scalaGuide.get.keywordScore shouldBe defined

      // RRF scores should be positive
      matches.foreach(_.score should be > 0.0)
    }

    "perform hybrid search with weighted score fusion" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)
      val queryText      = "Scala programming language"

      val results = searcher.search(
        queryEmbedding,
        queryText,
        topK = 3,
        strategy = FusionStrategy.WeightedScore(vectorWeight = 0.7, keywordWeight = 0.3)
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      matches.nonEmpty shouldBe true

      // Weighted scores should be in [0, 1] range
      matches.foreach { m =>
        m.score should be >= 0.0
        m.score should be <= 1.0
      }
    }

    "boost results that match both vector and keyword" in {
      // Query specifically designed to match scala-guide in both
      val queryEmbedding = Array(0.9f, 0.1f, 0.0f) // Very similar to scala-guide
      val queryText      = "Scala functional"      // Matches scala-guide content

      val results = searcher.search(
        queryEmbedding,
        queryText,
        topK = 5,
        strategy = FusionStrategy.RRF()
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity)

      // scala-guide should rank first (high in both)
      matches.head.id shouldBe "scala-guide"

      // It should have both scores
      matches.head.vectorScore shouldBe defined
      matches.head.keywordScore shouldBe defined
    }

    "include highlights from keyword search" in {
      val queryEmbedding = Array(0.5f, 0.5f, 0.0f)
      val queryText      = "programming"

      val results = searcher.search(
        queryEmbedding,
        queryText,
        topK = 5,
        strategy = FusionStrategy.RRF()
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity)

      // At least some results should have highlights
      val withHighlights = matches.filter(_.highlights.nonEmpty)
      withHighlights.nonEmpty shouldBe true
    }

    "respect metadata filters" in {
      // Add docs with different metadata
      vectorStore.upsert(
        VectorRecord("filtered-1", Array(0.5f, 0.5f, 0.0f), Some("Scala filtered"), Map("category" -> "a"))
      )
      vectorStore.upsert(
        VectorRecord("filtered-2", Array(0.5f, 0.5f, 0.0f), Some("Scala filtered"), Map("category" -> "b"))
      )
      keywordIndex.index(KeywordDocument("filtered-1", "Scala filtered", Map("category" -> "a")))
      keywordIndex.index(KeywordDocument("filtered-2", "Scala filtered", Map("category" -> "b")))

      val filter = Some(MetadataFilter.Equals("category", "a"))
      val results = searcher.search(
        Array(0.5f, 0.5f, 0.0f),
        "Scala filtered",
        topK = 10,
        filter = filter
      )

      val matches = results.fold(e => fail(s"Failed: ${e.formatted}"), identity).filter(_.id.startsWith("filtered"))
      matches.size shouldBe 1
      matches.head.id shouldBe "filtered-1"
    }

    "handle empty results gracefully" in {
      val queryEmbedding = Array(0.0f, 0.0f, 1.0f)  // Different from all docs
      val queryText      = "nonexistent xyz qwerty" // No matches

      val results = searcher.search(
        queryEmbedding,
        queryText,
        topK = 3
      )

      // Should return empty or very low scoring results
      results.fold(e => fail(s"Failed: ${e.formatted}"), _ => ())
    }

    "use default strategy when none specified" in {
      val defaultSearcher = HybridSearcher(vectorStore, keywordIndex, FusionStrategy.WeightedScore(0.6, 0.4))

      val results = defaultSearcher.search(
        Array(0.9f, 0.1f, 0.0f),
        "Scala",
        topK = 3
      )

      results.isRight shouldBe true
    }
  }

  "HybridSearcher factory" should {

    "create from in-memory stores" in {
      val result = HybridSearcher.inMemory()
      result.fold(e => fail(s"Failed: ${e.formatted}"), _.close())
    }

    "create from configuration" in {
      val config = HybridSearcher.Config.inMemory
        .withRRF(k = 30)

      val result = HybridSearcher(config)
      result.fold(e => fail(s"Failed: ${e.formatted}"), _.close())
    }

    "support weighted score configuration" in {
      val config = HybridSearcher.Config.inMemory
        .withWeightedScore(vectorWeight = 0.8, keywordWeight = 0.2)

      val result = HybridSearcher(config)
      result.fold(e => fail(s"Failed: ${e.formatted}"), _.close())
    }
  }

  "FusionStrategy" should {

    "have sensible RRF default" in {
      val rrf = FusionStrategy.RRF()
      rrf.k shouldBe 60
    }

    "have balanced WeightedScore default" in {
      val ws = FusionStrategy.WeightedScore()
      ws.vectorWeight shouldBe 0.5
      ws.keywordWeight shouldBe 0.5
    }

    "reject negative weights" in {
      an[IllegalArgumentException] should be thrownBy {
        FusionStrategy.WeightedScore(vectorWeight = -0.1, keywordWeight = 0.5)
      }
    }
  }
}
