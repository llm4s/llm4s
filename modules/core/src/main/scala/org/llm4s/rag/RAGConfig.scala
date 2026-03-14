package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.knowledgegraph.graphrag.GraphRAGConfig
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.rag.loader.{ DirectoryLoader, DocumentLoader, LoadingConfig }
import org.llm4s.rag.permissions.SearchIndex
import org.llm4s.trace.Tracing
import org.llm4s.vectorstore.FusionStrategy
import org.llm4s.rag.transform.QueryTransformer

/**
 * Configuration for RAG pipeline.
 *
 * Uses immutable copy pattern for fluent configuration.
 * All settings have sensible defaults for quick start.
 *
 * @example
 * {{{
 * // Minimal configuration
 * val config = RAGConfig()
 *   .withEmbeddings(EmbeddingProvider.OpenAI)
 *
 * // Full customization with SQLite
 * val config = RAGConfig()
 *   .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
 *   .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
 *   .withRRF(60)
 *   .withSQLite("./rag.db")
 *   .withLLM(llmClient)
 *
 * // Using PostgreSQL with pgvector
 * val config = RAGConfig()
 *   .withEmbeddings(EmbeddingProvider.OpenAI)
 *   .withPgVector("jdbc:postgresql://localhost:5432/mydb", "user", "pass", "embeddings")
 * }}}
 */
final case class RAGConfig(
  // Embedding settings
  embeddingProvider: EmbeddingProvider = EmbeddingProvider.OpenAI,
  embeddingModel: Option[String] = None,
  embeddingDimensions: Option[Int] = None,
  // Chunking settings
  chunkingStrategy: ChunkerFactory.Strategy = ChunkerFactory.Strategy.Sentence,
  chunkingConfig: ChunkingConfig = ChunkingConfig.default,
  // Search settings
  fusionStrategy: FusionStrategy = FusionStrategy.RRF(),
  topK: Int = 5,
  // Reranking settings
  rerankingStrategy: RerankingStrategy = RerankingStrategy.None,
  rerankTopK: Int = 30,
  // Storage settings
  vectorStorePath: Option[String] = None,
  keywordIndexPath: Option[String] = None,
  // PgVector settings
  pgVectorConnectionString: Option[String] = None,
  pgVectorUser: Option[String] = None,
  pgVectorPassword: Option[String] = None,
  pgVectorTableName: Option[String] = None,
  // Pg Keyword Index settings (for full PostgreSQL hybrid search)
  pgKeywordTableName: Option[String] = None,
  // Answer generation
  llmClient: Option[LLMClient] = None,
  systemPrompt: Option[String] = None,
  // Observability
  tracer: Option[Tracing] = None,
  // Document loading
  documentLoaders: Seq[DocumentLoader] = Seq.empty,
  loadingConfig: LoadingConfig = LoadingConfig.default,
  // Permission-based search index (for enterprise RAG)
  searchIndex: Option[SearchIndex] = None,
  // Pre-retrieval query transforms
  queryTransformers: Seq[QueryTransformer] = Seq.empty,
  // GraphRAG integration
  graphStore: Option[GraphStore] = None,
  graphRAGConfig: GraphRAGConfig = GraphRAGConfig()
) {

  // ========== Embedding Configuration ==========

  /** Configure embedding provider */
  def withEmbeddings(provider: EmbeddingProvider): RAGConfig =
    copy(embeddingProvider = provider)

  /** Configure embedding provider and model */
  def withEmbeddings(provider: EmbeddingProvider, model: String): RAGConfig =
    copy(embeddingProvider = provider, embeddingModel = Some(model))

  /** Configure embedding provider, model, and dimensions */
  def withEmbeddings(provider: EmbeddingProvider, model: String, dimensions: Int): RAGConfig =
    copy(embeddingProvider = provider, embeddingModel = Some(model), embeddingDimensions = Some(dimensions))

  /** Override embedding dimensions (auto-detected by default) */
  def withEmbeddingDimensions(dims: Int): RAGConfig =
    copy(embeddingDimensions = Some(dims))

  // ========== Chunking Configuration ==========

  /** Configure chunking strategy */
  def withChunking(strategy: ChunkerFactory.Strategy): RAGConfig =
    copy(chunkingStrategy = strategy)

  /** Configure chunking strategy with custom config */
  def withChunking(strategy: ChunkerFactory.Strategy, config: ChunkingConfig): RAGConfig =
    copy(chunkingStrategy = strategy, chunkingConfig = config)

  /** Configure chunking with size and overlap */
  def withChunking(strategy: ChunkerFactory.Strategy, size: Int, overlap: Int): RAGConfig =
    copy(
      chunkingStrategy = strategy,
      chunkingConfig = ChunkingConfig(targetSize = size, maxSize = (size * 1.5).toInt, overlap = overlap)
    )

  // ========== Search Configuration ==========

  /** Configure fusion strategy */
  def withFusion(strategy: FusionStrategy): RAGConfig =
    copy(fusionStrategy = strategy)

  /** Use RRF fusion with custom k parameter */
  def withRRF(k: Int = 60): RAGConfig =
    copy(fusionStrategy = FusionStrategy.RRF(k))

  /** Use weighted score fusion */
  def withWeightedScore(vectorWeight: Double = 0.5, keywordWeight: Double = 0.5): RAGConfig =
    copy(fusionStrategy = FusionStrategy.WeightedScore(vectorWeight, keywordWeight))

  /** Use vector search only (no keyword) */
  def vectorOnly: RAGConfig =
    copy(fusionStrategy = FusionStrategy.VectorOnly)

  /** Use keyword search only (no vector) */
  def keywordOnly: RAGConfig =
    copy(fusionStrategy = FusionStrategy.KeywordOnly)

  /** Configure number of results to return */
  def withTopK(k: Int): RAGConfig =
    copy(topK = k)

  // ========== Reranking Configuration ==========

  /** Configure reranking strategy */
  def withReranking(strategy: RerankingStrategy): RAGConfig =
    copy(rerankingStrategy = strategy)

  /** Use Cohere cross-encoder reranking */
  def withCohereReranking(model: String = "rerank-english-v3.0"): RAGConfig =
    copy(rerankingStrategy = RerankingStrategy.Cohere(model))

  /** Use LLM-based reranking */
  def withLLMReranking: RAGConfig =
    copy(rerankingStrategy = RerankingStrategy.LLM)

  /** Configure number of candidates to rerank */
  def withRerankTopK(k: Int): RAGConfig =
    copy(rerankTopK = k)

  // ========== Storage Configuration ==========

  /** Use SQLite for persistent storage */
  def withSQLite(dbPath: String): RAGConfig =
    copy(
      vectorStorePath = Some(dbPath),
      keywordIndexPath = Some(dbPath.replace(".db", "-fts.db")),
      pgVectorConnectionString = None
    )

  /**
   * Use PostgreSQL with pgvector extension for vector storage.
   *
   * Connects to localhost:5432/postgres with user "postgres" by default.
   * Uses "vectors" as the default table name.
   */
  def withPgVector(): RAGConfig =
    withPgVector("jdbc:postgresql://localhost:5432/postgres", "postgres", "", "vectors")

  /**
   * Use PostgreSQL with pgvector extension for vector storage.
   *
   * Connects to localhost:5432/postgres with user "postgres" by default.
   *
   * @param tableName Table name for vectors
   */
  def withPgVector(tableName: String): RAGConfig =
    withPgVector("jdbc:postgresql://localhost:5432/postgres", "postgres", "", tableName)

  /**
   * Use PostgreSQL with pgvector extension for vector storage.
   *
   * @param connectionString JDBC connection string (e.g., "jdbc:postgresql://host:port/database")
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for vectors
   */
  def withPgVector(
    connectionString: String,
    user: String,
    password: String,
    tableName: String
  ): RAGConfig =
    copy(
      vectorStorePath = None,
      pgVectorConnectionString = Some(connectionString),
      pgVectorUser = Some(user),
      pgVectorPassword = Some(password),
      pgVectorTableName = Some(tableName)
    )

  /** Use in-memory storage (default) */
  def inMemory: RAGConfig =
    copy(vectorStorePath = None, keywordIndexPath = None, pgVectorConnectionString = None, pgKeywordTableName = None)

  /**
   * Use PostgreSQL for both vector AND keyword search (full hybrid).
   *
   * This enables fully PostgreSQL-based hybrid RAG using:
   * - pgvector extension for vector similarity search
   * - PostgreSQL native full-text search (tsvector/tsquery) for keyword search
   *
   * Requires PostgreSQL 16+ with pgvector extension.
   * Recommended: PostgreSQL 18+ for best performance.
   *
   * @param connectionString JDBC connection string (e.g., "jdbc:postgresql://host:port/database")
   * @param user Database user
   * @param password Database password
   * @param vectorTableName Table name for vectors (default: "vectors")
   * @param keywordTableName Base table name for keywords (creates {tableName}_keyword table, default: "documents")
   */
  def withPgHybrid(
    connectionString: String,
    user: String,
    password: String,
    vectorTableName: String = "vectors",
    keywordTableName: String = "documents"
  ): RAGConfig =
    copy(
      vectorStorePath = None,
      keywordIndexPath = None,
      pgVectorConnectionString = Some(connectionString),
      pgVectorUser = Some(user),
      pgVectorPassword = Some(password),
      pgVectorTableName = Some(vectorTableName),
      pgKeywordTableName = Some(keywordTableName)
    )

  /**
   * Use PostgreSQL for both vector AND keyword search with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   */
  def withPgHybridLocal(
    vectorTableName: String = "vectors",
    keywordTableName: String = "documents"
  ): RAGConfig =
    withPgHybrid("jdbc:postgresql://localhost:5432/postgres", "postgres", "", vectorTableName, keywordTableName)

  // ========== Answer Generation Configuration ==========

  /** Configure LLM client for answer generation */
  def withLLM(client: LLMClient): RAGConfig =
    copy(llmClient = Some(client))

  /** Configure system prompt for answer generation */
  def withSystemPrompt(prompt: String): RAGConfig =
    copy(systemPrompt = Some(prompt))

  // ========== Observability Configuration ==========

  /** Enable tracing for cost tracking and observability */
  def withTracing(tracer: Tracing): RAGConfig =
    copy(tracer = Some(tracer))

  // ========== Pre-Retrieval Query Transform Configuration ==========

  /**
   * Add a query transformer for pre-retrieval processing.
   *
   * Transforms are applied sequentially before embedding.
   * Multiple transforms can be chained.
   *
   * @param transformer The query transformer to add
   */
  def withQueryTransformer(transformer: QueryTransformer): RAGConfig =
    copy(queryTransformers = queryTransformers :+ transformer)

  /**
   * Set all query transformers, replacing any existing ones.
   *
   * @param transformers The complete list of transformers
   */
  def withQueryTransformers(transformers: Seq[QueryTransformer]): RAGConfig =
    copy(queryTransformers = transformers)

  // ========== Document Loading Configuration ==========

  /**
   * Add a document loader for build-time ingestion.
   *
   * Documents will be ingested when RAG.build() is called.
   */
  def withDocuments(loader: DocumentLoader): RAGConfig =
    copy(documentLoaders = documentLoaders :+ loader)

  /**
   * Add documents from a directory path.
   */
  def withDocuments(path: String): RAGConfig =
    withDocuments(DirectoryLoader(path))

  /**
   * Add multiple document loaders.
   */
  def withDocuments(loaders: Seq[DocumentLoader]): RAGConfig =
    copy(documentLoaders = documentLoaders ++ loaders)

  /**
   * Configure document loading behavior.
   */
  def withLoadingConfig(config: LoadingConfig): RAGConfig =
    copy(loadingConfig = config)

  /**
   * Set whether to stop on first loading error.
   */
  def failOnLoadError(fail: Boolean): RAGConfig =
    copy(loadingConfig = loadingConfig.copy(failFast = fail))

  /**
   * Set parallelism for document processing.
   */
  def withParallelism(n: Int): RAGConfig =
    copy(loadingConfig = loadingConfig.copy(parallelism = n))

  /**
   * Set batch size for embedding operations.
   */
  def withBatchSize(n: Int): RAGConfig =
    copy(loadingConfig = loadingConfig.copy(batchSize = n))

  // ========== Permission-Based Search Configuration ==========

  /**
   * Configure a SearchIndex for permission-based RAG.
   *
   * When a SearchIndex is configured, you can use permission-aware
   * query and ingest methods on the RAG instance.
   *
   * IMPORTANT: When a PgSearchIndex is provided, this method automatically
   * configures the underlying pgVector storage settings so that both
   * regular ingest methods and permission-aware methods use the same
   * PostgreSQL database.
   *
   * @param index The SearchIndex instance (e.g., PgSearchIndex)
   */
  def withSearchIndex(index: SearchIndex): RAGConfig = {
    // If the SearchIndex is PostgreSQL-backed, automatically configure
    // the pgVector settings to ensure regular ingest/query methods also
    // use the same PostgreSQL database for vector storage.
    val baseConfig = copy(searchIndex = Some(index))

    index.pgConfig match {
      case Some(pgCfg) =>
        baseConfig.copy(
          pgVectorConnectionString = Some(pgCfg.jdbcUrl),
          pgVectorUser = Some(pgCfg.user),
          pgVectorPassword = Some(pgCfg.password),
          pgVectorTableName = Some(pgCfg.vectorTableName),
          pgKeywordTableName = Some(pgCfg.keywordTableName)
        )
      case None =>
        baseConfig
    }
  }

  /**
   * Configure PostgreSQL connection for pgvector storage.
   *
   * Note: This does NOT create a SearchIndex with permissions.
   * For permission-based RAG, you need to:
   * 1. Create a PgSearchIndex manually
   * 2. Call .withSearchIndex(index) on the config
   *
   * Example:
   * {{{
   * for {
   *   searchIndex <- PgSearchIndex.fromJdbcUrl(jdbcUrl, user, password)
   *   _           <- searchIndex.initializeSchema()
   *   rag <- RAG.builder()
   *     .withEmbeddings(EmbeddingProvider.OpenAI)
   *     .withSearchIndex(searchIndex)
   *     .build()
   * } yield rag
   * }}}
   *
   * @param connectionString JDBC connection string
   * @param user Database user
   * @param password Database password
   * @param vectorTableName Name of the vectors table
   */
  def withPgVectorPermissions(
    connectionString: String,
    user: String,
    password: String,
    vectorTableName: String = "vectors"
  ): RAGConfig =
    copy(
      pgVectorConnectionString = Some(connectionString),
      pgVectorUser = Some(user),
      pgVectorPassword = Some(password),
      pgVectorTableName = Some(vectorTableName)
    )

  /** Check if permission-based search is enabled */
  def hasPermissions: Boolean = searchIndex.isDefined

  // ========== GraphRAG Configuration ==========

  /**
   * Enable GraphRAG by attaching a GraphStore.
   *
   * GraphRAG queries require an LLM client for summary generation and synthesis.
   */
  def withGraphStore(store: GraphStore): RAGConfig =
    copy(graphStore = Some(store))

  /** Override GraphRAG tuning parameters. */
  def withGraphRAG(config: GraphRAGConfig): RAGConfig =
    copy(graphRAGConfig = config)

  /** Check if GraphRAG is enabled. */
  def hasGraphRAG: Boolean = graphStore.isDefined
}

object RAGConfig {

  /** Default configuration - OpenAI embeddings, sentence chunking, RRF fusion, in-memory */
  val default: RAGConfig = RAGConfig()

  /** Configuration for production use with persistent storage */
  def production(dbPath: String): RAGConfig =
    RAGConfig()
      .withSQLite(dbPath)
      .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)

  /** Configuration for development/testing with smaller top-k */
  val development: RAGConfig =
    RAGConfig().inMemory
      .withTopK(3)
}
