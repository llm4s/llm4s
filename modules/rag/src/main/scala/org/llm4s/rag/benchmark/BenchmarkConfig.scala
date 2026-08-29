package org.llm4s.rag.benchmark

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.vectorstore.FusionStrategy

/**
 * Configuration for embedding provider in benchmark experiments.
 *
 * Supports multiple embedding providers for comparison testing:
 * - OpenAI (text-embedding-3-small, text-embedding-3-large)
 * - Voyage AI (voyage-3, voyage-code-3)
 * - Ollama (nomic-embed-text, mxbai-embed-large)
 */
sealed trait EmbeddingConfig {
  def provider: String
  def model: String
  def dimensions: Int
}

object EmbeddingConfig {

  /**
   * OpenAI embedding configuration.
   *
   * @param model Model name (default: text-embedding-3-small)
   * @param dimensions Embedding dimensions (default: 1536)
   */
  final case class OpenAI(
    model: String = "text-embedding-3-small",
    dimensions: Int = 1536
  ) extends EmbeddingConfig {
    val provider: String = "openai"
  }

  /**
   * Voyage AI embedding configuration.
   *
   * @param model Model name (default: voyage-3)
   * @param dimensions Embedding dimensions (default: 1024)
   */
  final case class Voyage(
    model: String = "voyage-3",
    dimensions: Int = 1024
  ) extends EmbeddingConfig {
    val provider: String = "voyage"
  }

  /**
   * Ollama local embedding configuration.
   *
   * @param model Model name (default: nomic-embed-text)
   * @param dimensions Embedding dimensions (default: 768)
   * @param baseUrl Ollama server URL (default: http://localhost:11434)
   */
  final case class Ollama(
    model: String = "nomic-embed-text",
    dimensions: Int = 768,
    baseUrl: String = "http://localhost:11434"
  ) extends EmbeddingConfig {
    val provider: String = "ollama"
  }

  /** Default embedding configuration (OpenAI text-embedding-3-small) */
  val default: EmbeddingConfig = OpenAI()

  /** Pre-configured OpenAI large embedding model */
  val openAILarge: OpenAI = OpenAI("text-embedding-3-large", 3072)

  /** Pre-configured Voyage code-optimized model */
  val voyageCode: Voyage = Voyage("voyage-code-3", 1024)

  /** Pre-configured Ollama mxbai model */
  val ollamaMxbai: Ollama = Ollama("mxbai-embed-large", 1024)
}

/**
 * Configuration for a single RAG experiment.
 *
 * Defines all parameters that can vary between experiments:
 * - Chunking strategy and parameters
 * - Embedding provider and model
 * - Search fusion strategy
 * - Retrieval settings
 *
 * @param name Unique identifier for this experiment
 * @param description Human-readable description
 * @param chunkingStrategy Which chunker to use (Simple, Sentence, Markdown, Semantic)
 * @param chunkingConfig Parameters for chunking (size, overlap, etc.)
 * @param embeddingConfig Embedding provider configuration
 * @param fusionStrategy How to combine vector and keyword search results
 * @param topK Number of chunks to retrieve
 * @param useReranker Whether to apply cross-encoder reranking
 * @param rerankTopK Number of candidates for reranking (if enabled)
 *
 * @example
 * {{{
 * val config = RAGExperimentConfig(
 *   name = "sentence-rrf60",
 *   description = "Sentence chunking with RRF fusion",
 *   chunkingStrategy = ChunkerFactory.Strategy.Sentence,
 *   fusionStrategy = FusionStrategy.RRF(60),
 *   topK = 5
 * )
 * }}}
 */
final case class RAGExperimentConfig(
  name: String,
  description: String = "",
  chunkingStrategy: ChunkerFactory.Strategy = ChunkerFactory.Strategy.Sentence,
  chunkingConfig: ChunkingConfig = ChunkingConfig.default,
  embeddingConfig: EmbeddingConfig = EmbeddingConfig.default,
  fusionStrategy: FusionStrategy = FusionStrategy.default,
  topK: Int = 5,
  useReranker: Boolean = false,
  rerankTopK: Int = 30
) {
  require(name.nonEmpty, "Experiment name cannot be empty")
  require(topK > 0, "topK must be positive")
  require(rerankTopK >= topK, "rerankTopK must be >= topK")

  /** Short identifier for display */
  def shortName: String = name.take(30)

  /** Full description for reports */
  def fullDescription: String =
    if (description.nonEmpty) description
    else
      s"${chunkingStrategy.name} chunking, ${embeddingConfig.provider}/${embeddingConfig.model}, ${fusionStrategyName}"

  private def fusionStrategyName: String = fusionStrategy match {
    case FusionStrategy.RRF(k)                => s"RRF(k=$k)"
    case FusionStrategy.WeightedScore(vw, kw) => s"Weighted(v=$vw,k=$kw)"
    case FusionStrategy.VectorOnly            => "VectorOnly"
    case FusionStrategy.KeywordOnly           => "KeywordOnly"
  }
}

object RAGExperimentConfig {

  /** Default configuration: sentence chunking, OpenAI embeddings, RRF fusion */
  val default: RAGExperimentConfig = RAGExperimentConfig("default")

  /**
   * Create a configuration for testing chunking strategies.
   */
  def forChunking(
    strategy: ChunkerFactory.Strategy,
    config: ChunkingConfig = ChunkingConfig.default
  ): RAGExperimentConfig = RAGExperimentConfig(
    name = s"${strategy.name}-default",
    description = s"${strategy.name} chunking with default parameters",
    chunkingStrategy = strategy,
    chunkingConfig = config
  )

  /**
   * Create a configuration for testing embedding providers.
   */
  def forEmbedding(config: EmbeddingConfig): RAGExperimentConfig = RAGExperimentConfig(
    name = s"${config.provider}-${config.model}",
    description = s"${config.provider} embeddings with ${config.model}",
    embeddingConfig = config
  )

  /**
   * Create a configuration for testing fusion strategies.
   */
  def forFusion(strategy: FusionStrategy): RAGExperimentConfig = {
    val strategyName = strategy match {
      case FusionStrategy.RRF(k)               => s"rrf-$k"
      case FusionStrategy.WeightedScore(vw, _) => s"weighted-${(vw * 100).toInt}v"
      case FusionStrategy.VectorOnly           => "vector-only"
      case FusionStrategy.KeywordOnly          => "keyword-only"
    }
    RAGExperimentConfig(
      name = strategyName,
      description = s"Fusion strategy: $strategyName",
      fusionStrategy = strategy
    )
  }
}

/**
 * A suite of benchmark experiments to run together.
 *
 * Groups related experiments for systematic comparison.
 * Provides pre-built suites for common comparison scenarios.
 *
 * @param name Suite identifier
 * @param description What this suite tests
 * @param experiments The experiments in this suite
 * @param datasetPath Path to the evaluation dataset JSON file
 * @param subsetSize Optional limit on samples to evaluate (for quick tests)
 * @param seed Random seed for reproducible sample selection
 *
 * @example
 * {{{
 * val suite = BenchmarkSuite.chunkingSuite("data/datasets/ragbench/test.json")
 * val results = runner.runSuite(suite)
 * }}}
 */
final case class BenchmarkSuite(
  name: String,
  description: String,
  experiments: Seq[RAGExperimentConfig],
  datasetPath: String,
  subsetSize: Option[Int] = None,
  seed: Long = 42L
) {
  require(experiments.nonEmpty, "Suite must contain at least one experiment")

  /** Number of experiments in this suite */
  def size: Int = experiments.size

  /** Get experiment by name */
  def getExperiment(name: String): Option[RAGExperimentConfig] =
    experiments.find(_.name == name)

  /** Create a quick version with limited samples */
  def quick(sampleCount: Int = 10): BenchmarkSuite =
    copy(subsetSize = Some(sampleCount))
}

object BenchmarkSuite {

  /**
   * Suite comparing different chunking strategies.
   *
   * Tests: Simple, Sentence, Markdown chunkers with default parameters.
   * Semantic chunking is excluded as it requires embedding client setup.
   */
  def chunkingSuite(datasetPath: String): BenchmarkSuite = BenchmarkSuite(
    name = "chunking-comparison",
    description = "Compare different chunking strategies",
    experiments = Seq(
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Simple),
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Sentence),
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Markdown),
      RAGExperimentConfig(
        name = "sentence-large",
        description = "Sentence chunking with larger chunks (1200 target)",
        chunkingStrategy = ChunkerFactory.Strategy.Sentence,
        chunkingConfig = ChunkingConfig(targetSize = 1200, maxSize = 1800, overlap = 200)
      ),
      RAGExperimentConfig(
        name = "sentence-small",
        description = "Sentence chunking with smaller chunks (400 target)",
        chunkingStrategy = ChunkerFactory.Strategy.Sentence,
        chunkingConfig = ChunkingConfig(targetSize = 400, maxSize = 600, overlap = 100)
      )
    ),
    datasetPath = datasetPath
  )

  /**
   * Suite comparing different fusion strategies.
   *
   * Tests: RRF with different k values, weighted scoring, and single-source modes.
   */
  def fusionSuite(datasetPath: String): BenchmarkSuite = BenchmarkSuite(
    name = "fusion-comparison",
    description = "Compare different search fusion strategies",
    experiments = Seq(
      RAGExperimentConfig.forFusion(FusionStrategy.RRF(60)),
      RAGExperimentConfig.forFusion(FusionStrategy.RRF(20)),
      RAGExperimentConfig.forFusion(FusionStrategy.WeightedScore(0.7, 0.3)),
      RAGExperimentConfig.forFusion(FusionStrategy.WeightedScore(0.5, 0.5)),
      RAGExperimentConfig.forFusion(FusionStrategy.VectorOnly),
      RAGExperimentConfig.forFusion(FusionStrategy.KeywordOnly)
    ),
    datasetPath = datasetPath
  )

  /**
   * Suite comparing different embedding providers.
   *
   * Tests: OpenAI (small/large), Voyage, Ollama embeddings.
   * Note: Requires API keys for cloud providers and local Ollama for local models.
   */
  def embeddingSuite(datasetPath: String): BenchmarkSuite = BenchmarkSuite(
    name = "embedding-comparison",
    description = "Compare different embedding providers",
    experiments = Seq(
      RAGExperimentConfig.forEmbedding(EmbeddingConfig.OpenAI()),
      RAGExperimentConfig.forEmbedding(EmbeddingConfig.openAILarge),
      RAGExperimentConfig.forEmbedding(EmbeddingConfig.Voyage()),
      RAGExperimentConfig.forEmbedding(EmbeddingConfig.Ollama())
    ),
    datasetPath = datasetPath
  )

  /**
   * Quick test suite with minimal experiments for fast validation.
   *
   * Tests just two configurations with a small sample subset.
   */
  def quickSuite(datasetPath: String, sampleCount: Int = 10): BenchmarkSuite = BenchmarkSuite(
    name = "quick-test",
    description = "Quick validation suite",
    experiments = Seq(
      RAGExperimentConfig.default,
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Simple)
    ),
    datasetPath = datasetPath,
    subsetSize = Some(sampleCount)
  )

  /**
   * Comprehensive suite testing all major dimensions.
   *
   * Combines chunking, fusion, and basic embedding comparisons.
   * Use for thorough evaluation when time permits.
   */
  def comprehensiveSuite(datasetPath: String): BenchmarkSuite = BenchmarkSuite(
    name = "comprehensive",
    description = "Comprehensive comparison across chunking, fusion, and embeddings",
    experiments = Seq(
      // Chunking variations
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Simple),
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Sentence),
      RAGExperimentConfig.forChunking(ChunkerFactory.Strategy.Markdown),
      // Fusion variations
      RAGExperimentConfig.forFusion(FusionStrategy.RRF(60)),
      RAGExperimentConfig.forFusion(FusionStrategy.VectorOnly),
      // Embedding variations
      RAGExperimentConfig.forEmbedding(EmbeddingConfig.OpenAI()),
      RAGExperimentConfig.forEmbedding(EmbeddingConfig.openAILarge)
    ),
    datasetPath = datasetPath
  )

  /**
   * Create a custom suite from configurations.
   */
  def custom(
    name: String,
    description: String,
    experiments: Seq[RAGExperimentConfig],
    datasetPath: String
  ): BenchmarkSuite = BenchmarkSuite(name, description, experiments, datasetPath)
}
