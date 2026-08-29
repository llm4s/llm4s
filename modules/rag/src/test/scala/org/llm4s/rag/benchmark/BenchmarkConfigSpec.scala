package org.llm4s.rag.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.vectorstore.FusionStrategy

class BenchmarkConfigSpec extends AnyFlatSpec with Matchers {

  "EmbeddingConfig" should "have correct provider names" in {
    EmbeddingConfig.OpenAI().provider shouldBe "openai"
    EmbeddingConfig.Voyage().provider shouldBe "voyage"
    EmbeddingConfig.Ollama().provider shouldBe "ollama"
  }

  it should "have sensible default dimensions" in {
    EmbeddingConfig.OpenAI().dimensions shouldBe 1536
    EmbeddingConfig.Voyage().dimensions shouldBe 1024
    EmbeddingConfig.Ollama().dimensions shouldBe 768
  }

  it should "provide pre-configured variants" in {
    EmbeddingConfig.openAILarge.dimensions shouldBe 3072
    EmbeddingConfig.voyageCode.model shouldBe "voyage-code-3"
    EmbeddingConfig.ollamaMxbai.model shouldBe "mxbai-embed-large"
  }

  "RAGExperimentConfig" should "have a default configuration" in {
    val config = RAGExperimentConfig.default
    config.name shouldBe "default"
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.topK shouldBe 5
  }

  it should "require a non-empty name" in {
    an[IllegalArgumentException] should be thrownBy {
      RAGExperimentConfig(name = "")
    }
  }

  it should "require positive topK" in {
    an[IllegalArgumentException] should be thrownBy {
      RAGExperimentConfig(name = "test", topK = 0)
    }
  }

  it should "require rerankTopK >= topK" in {
    an[IllegalArgumentException] should be thrownBy {
      RAGExperimentConfig(name = "test", topK = 10, rerankTopK = 5)
    }
  }

  it should "create config for chunking strategy" in {
    val config = RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Markdown)
    config.name shouldBe "markdown-default"
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Markdown
  }

  it should "create config for embedding provider" in {
    val config = RAGExperimentConfig.forEmbedding(EmbeddingConfig.Voyage())
    config.name shouldBe "voyage-voyage-3"
    config.embeddingConfig shouldBe EmbeddingConfig.Voyage()
  }

  it should "create config for fusion strategy" in {
    val config = RAGExperimentConfig.forFusion(FusionStrategy.RRF(30))
    config.name shouldBe "rrf-30"
    config.fusionStrategy shouldBe FusionStrategy.RRF(30)
  }

  it should "generate full description" in {
    val config = RAGExperimentConfig(name = "test")
    config.fullDescription should include("sentence")
    config.fullDescription should include("openai")
    config.fullDescription should include("RRF")
  }

  "BenchmarkSuite" should "require at least one experiment" in {
    an[IllegalArgumentException] should be thrownBy {
      BenchmarkSuite("empty", "No experiments", Seq.empty, "path.json")
    }
  }

  it should "provide chunking suite" in {
    val suite = BenchmarkSuite.chunkingSuite("test.json")
    suite.name shouldBe "chunking-comparison"
    suite.experiments should have size 5
    // Simple, Sentence, Markdown (Semantic excluded - requires embedding client)
    suite.experiments.map(_.chunkingStrategy).distinct should have size 3
  }

  it should "provide fusion suite" in {
    val suite = BenchmarkSuite.fusionSuite("test.json")
    suite.name shouldBe "fusion-comparison"
    suite.experiments should have size 6
  }

  it should "provide embedding suite" in {
    val suite = BenchmarkSuite.embeddingSuite("test.json")
    suite.name shouldBe "embedding-comparison"
    suite.experiments should have size 4
  }

  it should "provide quick suite" in {
    val suite = BenchmarkSuite.quickSuite("test.json", 5)
    suite.name shouldBe "quick-test"
    suite.subsetSize shouldBe Some(5)
    suite.experiments should have size 2
  }

  it should "create quick version of a suite" in {
    val full  = BenchmarkSuite.chunkingSuite("test.json")
    val quick = full.quick(10)
    quick.subsetSize shouldBe Some(10)
    quick.experiments shouldBe full.experiments
  }

  it should "get experiment by name" in {
    val suite = BenchmarkSuite.chunkingSuite("test.json")
    suite.getExperiment("sentence-default") should be(defined)
    suite.getExperiment("nonexistent") shouldBe None
  }
}
