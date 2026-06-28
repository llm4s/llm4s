package org.llm4s.vectorstore

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.error.ProcessingError
import org.llm4s.reranker.{ RerankRequest, Reranker }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Result from hybrid search combining vector and keyword results.
 *
 * @param id Document ID
 * @param content Document content
 * @param score Combined relevance score (higher is better)
 * @param vectorScore Original vector similarity score (0-1, if available)
 * @param keywordScore Original BM25 keyword score (if available)
 * @param metadata Document metadata
 * @param highlights Keyword match highlights (if available)
 */
final case class HybridSearchResult(
  id: String,
  content: String,
  score: Double,
  vectorScore: Option[Double] = None,
  keywordScore: Option[Double] = None,
  metadata: Map[String, String] = Map.empty,
  highlights: Seq[String] = Seq.empty
)

/**
 * Fusion strategy for combining vector and keyword search results.
 */
sealed trait FusionStrategy

object FusionStrategy {

  /**
   * Reciprocal Rank Fusion (RRF).
   *
   * Combines results based on their rank positions across result lists.
   * Score = sum(1 / (k + rank)) across all lists where the document appears.
   *
   * RRF is robust to score scale differences and performs well when
   * combining results from heterogeneous sources.
   *
   * @param k Smoothing constant (default: 60, as per original RRF paper)
   */
  final case class RRF(k: Int = 60) extends FusionStrategy

  /**
   * Weighted score combination.
   *
   * Normalizes scores from each source to [0, 1] and combines with weights.
   * Score = vectorWeight * normalizedVectorScore + keywordWeight * normalizedKeywordScore
   *
   * @param vectorWeight Weight for vector similarity (default: 0.5)
   * @param keywordWeight Weight for keyword matching (default: 0.5)
   */
  final case class WeightedScore(
    vectorWeight: Double = 0.5,
    keywordWeight: Double = 0.5
  ) extends FusionStrategy {
    require(vectorWeight >= 0 && keywordWeight >= 0, "Weights must be non-negative")
  }

  /**
   * Vector-only search (no keyword fusion).
   */
  case object VectorOnly extends FusionStrategy

  /**
   * Keyword-only search (no vector fusion).
   */
  case object KeywordOnly extends FusionStrategy

  /** Default strategy: RRF with k=60 */
  val default: FusionStrategy = RRF()
}

/**
 * Hybrid searcher combining vector similarity and keyword matching.
 *
 * Provides unified search over both vector embeddings (semantic similarity)
 * and keyword indexes (BM25 term matching). Results are fused using
 * configurable strategies like RRF or weighted scoring.
 *
 * Usage:
 * {{{
 * for {
 *   vectorStore <- VectorStoreFactory.inMemory()
 *   keywordIndex <- KeywordIndex.inMemory()
 *   searcher = HybridSearcher(vectorStore, keywordIndex)
 *   // Add documents to both stores
 *   _ <- vectorStore.upsert(VectorRecord("doc-1", embedding, Some("content")))
 *   _ <- keywordIndex.index(KeywordDocument("doc-1", "content"))
 *   // Search with hybrid fusion
 *   results <- searcher.search(queryEmbedding, "search terms", topK = 10)
 * } yield results
 * }}}
 */
final class HybridSearcher private (
  val vectorStore: VectorStore,
  val keywordIndex: KeywordIndex,
  val defaultStrategy: FusionStrategy
) {

  /**
   * Perform hybrid search combining vector and keyword results.
   *
   * @param queryEmbedding Query embedding for vector search
   * @param queryText Query text for keyword search
   * @param topK Maximum results to return
   * @param strategy Fusion strategy (default: RRF)
   * @param filter Optional metadata filter applied to both searches
   * @return Fused search results ranked by combined score
   */
  def search(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int = 10,
    strategy: FusionStrategy = defaultStrategy,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[HybridSearchResult]] =
    strategy match {
      case FusionStrategy.VectorOnly =>
        searchVectorOnly(queryEmbedding, topK, filter)

      case FusionStrategy.KeywordOnly =>
        searchKeywordOnly(queryText, topK, filter)

      case rrf: FusionStrategy.RRF =>
        searchWithRRF(queryEmbedding, queryText, topK, rrf.k, filter)

      case ws: FusionStrategy.WeightedScore =>
        searchWithWeightedScore(queryEmbedding, queryText, topK, ws.vectorWeight, ws.keywordWeight, filter)
    }

  /**
   * Perform hybrid search with optional cross-encoder reranking.
   *
   * This method first performs hybrid search to get candidate documents,
   * then optionally applies a reranker for improved precision. Reranking
   * uses cross-encoder models that see both query and document together.
   *
   * @param queryEmbedding Query embedding for vector search
   * @param queryText Query text for keyword search and reranking
   * @param topK Maximum final results to return
   * @param rerankTopK Number of candidates to retrieve for reranking (default: 50)
   * @param strategy Fusion strategy (default: RRF)
   * @param filter Optional metadata filter
   * @param reranker Optional reranker (None = skip reranking)
   * @return Reranked search results
   *
   * @example
   * {{{
   * val reranker = RerankerFactory.cohere(apiKey)
   * val results = searcher.searchWithReranking(
   *   embedding, "search query",
   *   topK = 5,
   *   rerankTopK = 30,
   *   reranker = Some(reranker)
   * )
   * }}}
   */
  def searchWithReranking(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int = 10,
    rerankTopK: Int = 50,
    strategy: FusionStrategy = defaultStrategy,
    filter: Option[MetadataFilter] = None,
    reranker: Option[Reranker] = None
  ): Result[Seq[HybridSearchResult]] =
    for {
      // Get more candidates for reranking
      candidates <- search(queryEmbedding, queryText, rerankTopK, strategy, filter)

      // Apply reranking if provided
      reranked <- reranker match {
        case Some(r) =>
          val request = RerankRequest(
            query = queryText,
            documents = candidates.map(_.content),
            topK = Some(topK)
          )
          r.rerank(request).map { response =>
            response.results.map { rr =>
              // Preserve original metadata but update score from reranker
              candidates(rr.index).copy(score = rr.score)
            }
          }
        case None =>
          Right(candidates.take(topK))
      }
    } yield reranked

  /**
   * Search with vector similarity only.
   */
  def searchVectorOnly(
    queryEmbedding: Array[Float],
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[HybridSearchResult]] =
    vectorStore.search(queryEmbedding, topK, filter).map { results =>
      results.map { scored =>
        HybridSearchResult(
          id = scored.record.id,
          content = scored.record.content.getOrElse(""),
          score = scored.score,
          vectorScore = Some(scored.score),
          keywordScore = None,
          metadata = scored.record.metadata
        )
      }
    }

  /**
   * Search with keyword matching only.
   */
  def searchKeywordOnly(
    queryText: String,
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[HybridSearchResult]] =
    keywordIndex.searchWithHighlights(queryText, topK, filter = filter).map { results =>
      results.map { ksr =>
        HybridSearchResult(
          id = ksr.id,
          content = ksr.content,
          score = ksr.score,
          vectorScore = None,
          keywordScore = Some(ksr.score),
          metadata = ksr.metadata,
          highlights = ksr.highlights
        )
      }
    }

  /**
   * Search with Reciprocal Rank Fusion.
   */
  private def searchWithRRF(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int,
    k: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[HybridSearchResult]] =
    for {
      // Fetch more results than needed for better fusion
      vectorResults  <- vectorStore.search(queryEmbedding, topK * 2, filter)
      keywordResults <- keywordIndex.searchWithHighlights(queryText, topK * 2, filter = filter)
    } yield {
      // Build maps for quick lookup
      val vectorMap: Map[String, (Int, ScoredRecord)] =
        vectorResults.zipWithIndex.map { case (sr, idx) => sr.record.id -> (idx + 1, sr) }.toMap

      val keywordMap: Map[String, (Int, KeywordSearchResult)] =
        keywordResults.zipWithIndex.map { case (ksr, idx) => ksr.id -> (idx + 1, ksr) }.toMap

      // Collect all unique IDs
      val allIds = (vectorMap.keySet ++ keywordMap.keySet).toSeq

      // Calculate RRF scores
      val fused = allIds.map { id =>
        val vectorContribution  = vectorMap.get(id).map { case (rank, _) => 1.0 / (k + rank) }.getOrElse(0.0)
        val keywordContribution = keywordMap.get(id).map { case (rank, _) => 1.0 / (k + rank) }.getOrElse(0.0)
        val rrfScore            = vectorContribution + keywordContribution

        val vectorData  = vectorMap.get(id).map(_._2)
        val keywordData = keywordMap.get(id).map(_._2)

        HybridSearchResult(
          id = id,
          content = vectorData.flatMap(_.record.content).orElse(keywordData.map(_.content)).getOrElse(""),
          score = rrfScore,
          vectorScore = vectorData.map(_.score),
          keywordScore = keywordData.map(_.score),
          metadata = vectorData.map(_.record.metadata).orElse(keywordData.map(_.metadata)).getOrElse(Map.empty),
          highlights = keywordData.map(_.highlights).getOrElse(Seq.empty)
        )
      }

      // Sort by RRF score descending and take top K
      fused.sortBy(-_.score).take(topK)
    }

  /**
   * Search with weighted score combination.
   */
  private def searchWithWeightedScore(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int,
    vectorWeight: Double,
    keywordWeight: Double,
    filter: Option[MetadataFilter]
  ): Result[Seq[HybridSearchResult]] =
    for {
      vectorResults  <- vectorStore.search(queryEmbedding, topK * 2, filter)
      keywordResults <- keywordIndex.searchWithHighlights(queryText, topK * 2, filter = filter)
    } yield {
      // Normalize scores to [0, 1]
      val vectorScores = vectorResults.map(_.score)
      val (vectorMin, vectorMax) =
        if (vectorScores.isEmpty) (0.0, 1.0)
        else (vectorScores.min, vectorScores.max)

      val keywordScores = keywordResults.map(_.score)
      val (keywordMin, keywordMax) =
        if (keywordScores.isEmpty) (0.0, 1.0)
        else (keywordScores.min, keywordScores.max)

      def normalizeVector(score: Double): Double =
        if (vectorMax == vectorMin) 1.0
        else (score - vectorMin) / (vectorMax - vectorMin)

      def normalizeKeyword(score: Double): Double =
        if (keywordMax == keywordMin) 1.0
        else (score - keywordMin) / (keywordMax - keywordMin)

      // Build maps
      val vectorMap: Map[String, ScoredRecord] =
        vectorResults.map(sr => sr.record.id -> sr).toMap

      val keywordMap: Map[String, KeywordSearchResult] =
        keywordResults.map(ksr => ksr.id -> ksr).toMap

      val allIds      = (vectorMap.keySet ++ keywordMap.keySet).toSeq
      val totalWeight = vectorWeight + keywordWeight

      val fused = allIds.map { id =>
        val normalizedVectorScore  = vectorMap.get(id).map(sr => normalizeVector(sr.score))
        val normalizedKeywordScore = keywordMap.get(id).map(ksr => normalizeKeyword(ksr.score))

        val combinedScore =
          (normalizedVectorScore.getOrElse(0.0) * vectorWeight +
            normalizedKeywordScore.getOrElse(0.0) * keywordWeight) / totalWeight

        val vectorData  = vectorMap.get(id)
        val keywordData = keywordMap.get(id)

        HybridSearchResult(
          id = id,
          content = vectorData.flatMap(_.record.content).orElse(keywordData.map(_.content)).getOrElse(""),
          score = combinedScore,
          vectorScore = vectorData.map(_.score),
          keywordScore = keywordData.map(_.score),
          metadata = vectorData.map(_.record.metadata).orElse(keywordData.map(_.metadata)).getOrElse(Map.empty),
          highlights = keywordData.map(_.highlights).getOrElse(Seq.empty)
        )
      }

      fused.sortBy(-_.score).take(topK)
    }

  /**
   * Close both the vector store and keyword index.
   */
  def close(): Unit = {
    vectorStore.close()
    keywordIndex.close()
  }
}

object HybridSearcher {

  /**
   * Create a hybrid searcher from existing stores.
   *
   * @param vectorStore Vector store for semantic search
   * @param keywordIndex Keyword index for BM25 search
   * @param defaultStrategy Default fusion strategy (default: RRF)
   * @return Hybrid searcher
   */
  def apply(
    vectorStore: VectorStore,
    keywordIndex: KeywordIndex,
    defaultStrategy: FusionStrategy = FusionStrategy.default
  ): HybridSearcher =
    new HybridSearcher(vectorStore, keywordIndex, defaultStrategy)

  /**
   * Create a hybrid searcher with in-memory stores.
   *
   * @return Hybrid searcher or error
   */
  def inMemory(): Result[HybridSearcher] =
    for {
      vectorStore  <- VectorStoreFactory.inMemory()
      keywordIndex <- KeywordIndex.inMemory()
    } yield new HybridSearcher(vectorStore, keywordIndex, FusionStrategy.default)

  /**
   * Create a hybrid searcher with SQLite file-based stores.
   *
   * @param vectorDbPath Path to vector store database
   * @param keywordDbPath Path to keyword index database
   * @return Hybrid searcher or error
   */
  def sqlite(vectorDbPath: String, keywordDbPath: String): Result[HybridSearcher] =
    for {
      vectorStore  <- VectorStoreFactory.sqlite(vectorDbPath)
      keywordIndex <- SQLiteKeywordIndex(keywordDbPath)
    } yield new HybridSearcher(vectorStore, keywordIndex, FusionStrategy.default)

  /**
   * Create a hybrid searcher with PostgreSQL backends for both vector and keyword search.
   *
   * This enables fully PostgreSQL-based hybrid RAG using:
   * - pgvector extension for vector similarity search
   * - PostgreSQL native full-text search (tsvector/tsquery) for keyword search
   *
   * Both stores share a connection pool for efficiency.
   *
   * Requires PostgreSQL 16+ with pgvector extension installed.
   * Recommended: PostgreSQL 18+ for best performance.
   *
   * @param connectionString JDBC connection string (e.g., "jdbc:postgresql://localhost:5432/mydb")
   * @param user Database user
   * @param password Database password
   * @param vectorTableName Table name for vectors (default: "vectors")
   * @param keywordTableName Base table name for keywords (creates {tableName}_keyword table, default: "documents")
   * @param defaultStrategy Default fusion strategy (default: RRF with k=60)
   * @return Hybrid searcher or error
   */
  def pgvectorShared(
    connectionString: String,
    user: String = "postgres",
    password: String = "",
    vectorTableName: String = "vectors",
    keywordTableName: String = "documents",
    defaultStrategy: FusionStrategy = FusionStrategy.default
  ): Result[HybridSearcher] =
    Try {
      // Create shared HikariDataSource
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(connectionString)
      hikariConfig.setUsername(user)
      hikariConfig.setPassword(password)
      hikariConfig.setMaximumPoolSize(20) // Larger pool for shared usage
      hikariConfig.setMinimumIdle(2)
      hikariConfig.setConnectionTimeout(HikariDefaults.CONNECTION_TIMEOUT_MS)
      hikariConfig.setIdleTimeout(HikariDefaults.IDLE_TIMEOUT_MS)
      hikariConfig.setMaxLifetime(HikariDefaults.MAX_LIFETIME_MS)

      new HikariDataSource(hikariConfig)
    }.toEither.left
      .map(e => ProcessingError("hybrid-searcher", s"Failed to create connection pool: ${e.getMessage}"))
      .flatMap { dataSource =>
        for {
          vectorStore  <- PgVectorStore(dataSource, vectorTableName)
          keywordIndex <- PgKeywordIndex(dataSource, keywordTableName)
        } yield new HybridSearcher(vectorStore, keywordIndex, defaultStrategy)
      }

  /**
   * Create a hybrid searcher with PostgreSQL backends using separate connection pools.
   *
   * Use this when you need independent pool management for vector and keyword stores.
   * For most use cases, prefer `pgvectorShared` which shares a single pool.
   *
   * @param vectorConfig Configuration for PgVectorStore
   * @param keywordConfig Configuration for PgKeywordIndex
   * @param defaultStrategy Default fusion strategy
   * @return Hybrid searcher or error
   */
  def pgvector(
    vectorConfig: PgVectorStore.Config,
    keywordConfig: PgKeywordIndex.Config,
    defaultStrategy: FusionStrategy = FusionStrategy.default
  ): Result[HybridSearcher] =
    for {
      vectorStore  <- PgVectorStore(vectorConfig)
      keywordIndex <- PgKeywordIndex(keywordConfig)
    } yield new HybridSearcher(vectorStore, keywordIndex, defaultStrategy)

  /**
   * Configuration for hybrid searcher.
   *
   * @param vectorStoreConfig Vector store configuration
   * @param keywordIndexConfig Keyword index configuration
   * @param defaultStrategy Default fusion strategy
   */
  final case class Config(
    vectorStoreConfig: VectorStoreFactory.Config = VectorStoreFactory.Config.default,
    keywordIndexConfig: SQLiteKeywordIndex.Config = SQLiteKeywordIndex.Config.default,
    defaultStrategy: FusionStrategy = FusionStrategy.default
  ) {
    def withVectorStore(config: VectorStoreFactory.Config): Config =
      copy(vectorStoreConfig = config)

    def withKeywordIndex(config: SQLiteKeywordIndex.Config): Config =
      copy(keywordIndexConfig = config)

    def withStrategy(strategy: FusionStrategy): Config =
      copy(defaultStrategy = strategy)

    def withRRF(k: Int = 60): Config =
      copy(defaultStrategy = FusionStrategy.RRF(k))

    def withWeightedScore(vectorWeight: Double = 0.5, keywordWeight: Double = 0.5): Config =
      copy(defaultStrategy = FusionStrategy.WeightedScore(vectorWeight, keywordWeight))
  }

  object Config {
    val default: Config  = Config()
    val inMemory: Config = Config(VectorStoreFactory.Config.inMemory, SQLiteKeywordIndex.Config.inMemory)
  }

  /**
   * Create a hybrid searcher from configuration.
   *
   * @param config Hybrid searcher configuration
   * @return Hybrid searcher or error
   */
  def apply(config: Config): Result[HybridSearcher] =
    for {
      vectorStore  <- VectorStoreFactory.create(config.vectorStoreConfig)
      keywordIndex <- SQLiteKeywordIndex(config.keywordIndexConfig)
    } yield new HybridSearcher(vectorStore, keywordIndex, config.defaultStrategy)
}
