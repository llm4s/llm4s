package org.llm4s.rag.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.vectorstore.FusionStrategy

class BenchmarkConfigEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // RAGExperimentConfig.shortName
  // =========================================================================

  "RAGExperimentConfig.shortName" should "return the full name when under 30 chars" in {
    val config = RAGExperimentConfig(name = "short-name")
    config.shortName shouldBe "short-name"
  }

  it should "truncate names longer than 30 characters" in {
    val longName = "a" * 50
    val config   = RAGExperimentConfig(name = longName)
    (config.shortName should have).length(30)
    config.shortName shouldBe "a" * 30
  }

  it should "return exactly 30 chars for a 30-char name" in {
    val name30 = "x" * 30
    val config = RAGExperimentConfig(name = name30)
    config.shortName shouldBe name30
  }

  // =========================================================================
  // RAGExperimentConfig.fullDescription with different fusion strategies
  // =========================================================================

  "RAGExperimentConfig.fullDescription" should "use custom description when provided" in {
    val config = RAGExperimentConfig(name = "test", description = "My custom description")
    config.fullDescription shouldBe "My custom description"
  }

  it should "generate description with WeightedScore fusion strategy" in {
    val config = RAGExperimentConfig(
      name = "weighted-test",
      fusionStrategy = FusionStrategy.WeightedScore(0.6, 0.4)
    )
    config.fullDescription should include("Weighted(v=0.6,k=0.4)")
  }

  it should "generate description with VectorOnly fusion strategy" in {
    val config = RAGExperimentConfig(
      name = "vector-test",
      fusionStrategy = FusionStrategy.VectorOnly
    )
    config.fullDescription should include("VectorOnly")
  }

  it should "generate description with KeywordOnly fusion strategy" in {
    val config = RAGExperimentConfig(
      name = "keyword-test",
      fusionStrategy = FusionStrategy.KeywordOnly
    )
    config.fullDescription should include("KeywordOnly")
  }

  it should "generate description with RRF fusion strategy" in {
    val config = RAGExperimentConfig(
      name = "rrf-test",
      fusionStrategy = FusionStrategy.RRF(30)
    )
    config.fullDescription should include("RRF(k=30)")
  }

  it should "include embedding provider and model in generated description" in {
    val config = RAGExperimentConfig(
      name = "embed-test",
      embeddingConfig = EmbeddingConfig.Voyage("voyage-3", 1024)
    )
    config.fullDescription should include("voyage")
    config.fullDescription should include("voyage-3")
  }

  // =========================================================================
  // RAGExperimentConfig validation edge cases
  // =========================================================================

  "RAGExperimentConfig" should "accept topK of 1 (minimum valid)" in {
    val config = RAGExperimentConfig(name = "min-topk", topK = 1, rerankTopK = 1)
    config.topK shouldBe 1
  }

  it should "reject negative topK" in {
    an[IllegalArgumentException] should be thrownBy {
      RAGExperimentConfig(name = "test", topK = -1)
    }
  }

  it should "accept rerankTopK equal to topK" in {
    val config = RAGExperimentConfig(name = "test", topK = 10, rerankTopK = 10)
    config.rerankTopK shouldBe 10
  }

  // =========================================================================
  // RAGExperimentConfig factory methods - forFusion edge cases
  // =========================================================================

  "RAGExperimentConfig.forFusion" should "create config for WeightedScore" in {
    val config = RAGExperimentConfig.forFusion(FusionStrategy.WeightedScore(0.5, 0.5))
    config.name shouldBe "weighted-50v"
    config.description should include("weighted-50v")
  }

  it should "create config for VectorOnly" in {
    val config = RAGExperimentConfig.forFusion(FusionStrategy.VectorOnly)
    config.name shouldBe "vector-only"
  }

  it should "create config for KeywordOnly" in {
    val config = RAGExperimentConfig.forFusion(FusionStrategy.KeywordOnly)
    config.name shouldBe "keyword-only"
  }

  // =========================================================================
  // BenchmarkSuite.size
  // =========================================================================

  "BenchmarkSuite.size" should "return the number of experiments" in {
    val suite = BenchmarkSuite.chunkingSuite("test.json")
    suite.size shouldBe suite.experiments.size
  }

  it should "return 1 for a single-experiment suite" in {
    val suite = BenchmarkSuite(
      name = "single",
      description = "Single experiment",
      experiments = Seq(RAGExperimentConfig.default),
      datasetPath = "test.json"
    )
    suite.size shouldBe 1
  }

  // =========================================================================
  // BenchmarkSuite.custom
  // =========================================================================

  "BenchmarkSuite.custom" should "create a suite with given parameters" in {
    val experiments = Seq(
      RAGExperimentConfig(name = "exp1"),
      RAGExperimentConfig(name = "exp2")
    )
    val suite = BenchmarkSuite.custom("my-suite", "My description", experiments, "data.json")

    suite.name shouldBe "my-suite"
    suite.description shouldBe "My description"
    suite.experiments shouldBe experiments
    suite.datasetPath shouldBe "data.json"
    suite.subsetSize shouldBe None
  }

  // =========================================================================
  // BenchmarkSuite.comprehensiveSuite
  // =========================================================================

  "BenchmarkSuite.comprehensiveSuite" should "create suite with chunking, fusion, and embedding experiments" in {
    val suite = BenchmarkSuite.comprehensiveSuite("data.json")
    suite.name shouldBe "comprehensive"
    suite.experiments.size shouldBe 7
    suite.datasetPath shouldBe "data.json"
  }

  // =========================================================================
  // EmbeddingConfig variants
  // =========================================================================

  "EmbeddingConfig" should "have correct default model for each provider" in {
    EmbeddingConfig.OpenAI().model shouldBe "text-embedding-3-small"
    EmbeddingConfig.Voyage().model shouldBe "voyage-3"
    EmbeddingConfig.Ollama().model shouldBe "nomic-embed-text"
  }

  it should "allow custom dimensions for OpenAI" in {
    val config = EmbeddingConfig.OpenAI(dimensions = 512)
    config.dimensions shouldBe 512
    config.provider shouldBe "openai"
  }

  it should "allow custom base URL for Ollama" in {
    val config = EmbeddingConfig.Ollama(baseUrl = "http://remote:11434")
    config.baseUrl shouldBe "http://remote:11434"
  }

  "EmbeddingConfig.default" should "be OpenAI text-embedding-3-small" in {
    val default = EmbeddingConfig.default
    default.provider shouldBe "openai"
    default.model shouldBe "text-embedding-3-small"
    default.dimensions shouldBe 1536
  }

  // =========================================================================
  // BenchmarkSuite.quick
  // =========================================================================

  "BenchmarkSuite.quick" should "preserve experiments while setting subsetSize" in {
    val suite = BenchmarkSuite.fusionSuite("test.json")
    val quick = suite.quick(5)
    quick.experiments shouldBe suite.experiments
    quick.subsetSize shouldBe Some(5)
    quick.name shouldBe suite.name
    quick.description shouldBe suite.description
  }

  it should "use default of 10 samples" in {
    val suite = BenchmarkSuite.fusionSuite("test.json")
    val quick = suite.quick()
    quick.subsetSize shouldBe Some(10)
  }
}
