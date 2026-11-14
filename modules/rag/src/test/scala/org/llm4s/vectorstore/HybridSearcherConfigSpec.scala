package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Tests for HybridSearcher.Config builders and strategy configuration in HybridSearcher(config).
 */
class HybridSearcherConfigSpec extends AnyWordSpec with Matchers {

  // Sample documents with embeddings
  private val docs = Seq(
    ("scala-guide", "Scala is a powerful functional programming language", Array(0.9f, 0.1f, 0.0f)),
    ("scala-jvm", "Scala runs on the JVM and interoperates with Java", Array(0.8f, 0.2f, 0.1f)),
    ("python-intro", "Python is a dynamically typed programming language", Array(0.2f, 0.9f, 0.0f)),
    ("java-basics", "Java is an object-oriented programming language", Array(0.3f, 0.8f, 0.2f)),
    ("rust-systems", "Rust is a systems programming language focused on safety", Array(0.1f, 0.3f, 0.9f))
  )

  "HybridSearcher.Config" should {

    "define default configuration" in {
      val config = HybridSearcher.Config.default
      config.defaultStrategy shouldBe FusionStrategy.default
      config.vectorStoreConfig shouldBe VectorStoreFactory.Config.default
      config.keywordIndexConfig shouldBe SQLiteKeywordIndex.Config.default
    }

    "define inMemory configuration" in {
      val config = HybridSearcher.Config.inMemory
      config.vectorStoreConfig shouldBe VectorStoreFactory.Config.inMemory
      config.keywordIndexConfig shouldBe SQLiteKeywordIndex.Config.inMemory
      config.defaultStrategy shouldBe FusionStrategy.default
    }

    "change only defaultStrategy with withRRF()" in {
      val base    = HybridSearcher.Config.inMemory
      val updated = base.withRRF()
      updated.defaultStrategy shouldBe FusionStrategy.RRF(60)
      updated.vectorStoreConfig shouldBe base.vectorStoreConfig
      updated.keywordIndexConfig shouldBe base.keywordIndexConfig
    }

    "change only defaultStrategy with withRRF(k)" in {
      val base    = HybridSearcher.Config.inMemory
      val updated = base.withRRF(10)
      updated.defaultStrategy shouldBe FusionStrategy.RRF(10)
      updated.vectorStoreConfig shouldBe base.vectorStoreConfig
      updated.keywordIndexConfig shouldBe base.keywordIndexConfig
    }

    "change only defaultStrategy with withWeightedScore()" in {
      val base    = HybridSearcher.Config.inMemory
      val updated = base.withWeightedScore()
      updated.defaultStrategy shouldBe FusionStrategy.WeightedScore(0.5, 0.5)
      updated.vectorStoreConfig shouldBe base.vectorStoreConfig
      updated.keywordIndexConfig shouldBe base.keywordIndexConfig
    }

    "change only defaultStrategy with withWeightedScore(vectorWeight, keywordWeight)" in {
      val base    = HybridSearcher.Config.inMemory
      val updated = base.withWeightedScore(0.7, 0.3)
      updated.defaultStrategy shouldBe FusionStrategy.WeightedScore(0.7, 0.3)
      updated.vectorStoreConfig shouldBe base.vectorStoreConfig
      updated.keywordIndexConfig shouldBe base.keywordIndexConfig
    }

    "change only defaultStrategy with withStrategy" in {
      val base    = HybridSearcher.Config.inMemory
      val updated = base.withStrategy(FusionStrategy.VectorOnly)
      updated.defaultStrategy shouldBe FusionStrategy.VectorOnly
      updated.vectorStoreConfig shouldBe base.vectorStoreConfig
      updated.keywordIndexConfig shouldBe base.keywordIndexConfig
    }

    "change only vectorStoreConfig with withVectorStore" in {
      val base               = HybridSearcher.Config.inMemory
      val customVectorConfig = VectorStoreFactory.Config(VectorStoreFactory.Backend.SQLite, Some("custom-vector.db"))
      val updated            = base.withVectorStore(customVectorConfig)
      updated.vectorStoreConfig shouldBe customVectorConfig
      updated.keywordIndexConfig shouldBe base.keywordIndexConfig
      updated.defaultStrategy shouldBe base.defaultStrategy
    }

    "change only keywordIndexConfig with withKeywordIndex" in {
      val base                = HybridSearcher.Config.inMemory
      val customKeywordConfig = SQLiteKeywordIndex.Config.file("custom-keyword.db")
      val updated             = base.withKeywordIndex(customKeywordConfig)
      updated.keywordIndexConfig shouldBe customKeywordConfig
      updated.vectorStoreConfig shouldBe base.vectorStoreConfig
      updated.defaultStrategy shouldBe base.defaultStrategy
    }
  }

  "HybridSearcher.apply(config)" should {

    "build searcher with default strategy" in {
      val result   = HybridSearcher(HybridSearcher.Config.inMemory)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try
        searcher.defaultStrategy shouldBe FusionStrategy.default
      finally
        searcher.close()
    }

    "build searcher with configured weighted score strategy" in {
      val config   = HybridSearcher.Config.inMemory.withWeightedScore(0.7, 0.3)
      val result   = HybridSearcher(config)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try
        searcher.defaultStrategy shouldBe FusionStrategy.WeightedScore(0.7, 0.3)
      finally
        searcher.close()
    }

    "build searcher with configured RRF strategy" in {
      val config   = HybridSearcher.Config.inMemory.withRRF(10)
      val result   = HybridSearcher(config)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try
        searcher.defaultStrategy shouldBe FusionStrategy.RRF(10)
      finally
        searcher.close()
    }

    "build searcher with default RRF strategy from withRRF()" in {
      val config   = HybridSearcher.Config.inMemory.withRRF()
      val result   = HybridSearcher(config)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try
        searcher.defaultStrategy shouldBe FusionStrategy.RRF(60)
      finally
        searcher.close()
    }

    "build searcher with default weighted score strategy from withWeightedScore()" in {
      val config   = HybridSearcher.Config.inMemory.withWeightedScore()
      val result   = HybridSearcher(config)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try
        searcher.defaultStrategy shouldBe FusionStrategy.WeightedScore(0.5, 0.5)
      finally
        searcher.close()
    }

    "build searcher with configured strategy from withStrategy" in {
      val config   = HybridSearcher.Config.inMemory.withStrategy(FusionStrategy.KeywordOnly)
      val result   = HybridSearcher(config)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try
        searcher.defaultStrategy shouldBe FusionStrategy.KeywordOnly
      finally
        searcher.close()
    }

    "use configured strategy when searching without an explicit strategy" in {
      val config   = HybridSearcher.Config.inMemory.withStrategy(FusionStrategy.VectorOnly)
      val result   = HybridSearcher(config)
      val searcher = result.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      try {
        searcher.defaultStrategy shouldBe FusionStrategy.VectorOnly

        // Populate searcher's own vectorStore and keywordIndex
        docs.foreach { case (id, content, embedding) =>
          searcher.vectorStore.upsert(VectorRecord(id, embedding, Some(content), Map("type" -> "doc")))
          searcher.keywordIndex.index(KeywordDocument(id, content, Map("type" -> "doc")))
        }

        val searchResult = searcher.search(
          Array(0.85f, 0.15f, 0.0f),
          "Scala functional programming",
          topK = 2
        )

        val matches = searchResult.fold(e => fail(s"Failed: ${e.formatted}"), identity)
        matches.size shouldBe 2
        matches.foreach { m =>
          m.vectorScore shouldBe defined
          m.keywordScore shouldBe None
        }
      } finally searcher.close()
    }
  }
}
