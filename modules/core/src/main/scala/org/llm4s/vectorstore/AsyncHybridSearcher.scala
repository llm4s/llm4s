package org.llm4s.vectorstore

import org.llm4s.error.ProcessingError
import org.llm4s.reranker.{ RerankRequest, Reranker }
import org.llm4s.types.AsyncResult

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Asynchronous Hybrid Searcher.
 *
 * Provides unified, non-blocking search over both vector embeddings (semantic similarity)
 * and keyword indexes (BM25 term matching). Features complete architectural
 * concurrency—vector search and keyword search execute in parallel across independent
 * blocking thread configurations, optimizing latency during RRF/Weighted queries.
 */
final class AsyncHybridSearcher private (
  val vectorStore: AsyncVectorStore,
  val keywordIndex: AsyncKeywordIndex,
  val defaultStrategy: FusionStrategy
) {

  def search(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int = 10,
    strategy: FusionStrategy = defaultStrategy,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[HybridSearchResult]] =
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

  def searchWithReranking(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int = 10,
    rerankTopK: Int = 50,
    strategy: FusionStrategy = defaultStrategy,
    filter: Option[MetadataFilter] = None,
    reranker: Option[Reranker] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[HybridSearchResult]] =
    // 1. Await concurrent candidates
    search(queryEmbedding, queryText, rerankTopK, strategy, filter).flatMap {
      case Left(err)         => Future.successful(Left(err))
      case Right(candidates) =>
        // 2. Apply reranking if provided
        reranker match {
          case Some(r) =>
            val request = RerankRequest(
              query = queryText,
              documents = candidates.map(_.content),
              topK = Some(topK)
            )
            // Assuming r.rerank returns Future-wrapped Result (AsyncResult) in an async architecture
            // but if it is synchronous Result, wrap it:
            Future(r.rerank(request))
              .recover { case ex: Throwable =>
                Left(ProcessingError("reranking", s"Reranking failed: ${ex.getMessage}"))
              }
              .map {
                case Left(err) => Left(err)
                case Right(response) =>
                  Right(
                    response.results.flatMap(rr => candidates.lift(rr.index).map(_.copy(score = rr.score)))
                  )
              }
          case None =>
            Future.successful(Right(candidates.take(topK)))
        }
    }

  def searchVectorOnly(
    queryEmbedding: Array[Float],
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[HybridSearchResult]] =
    vectorStore.search(queryEmbedding, topK, filter).map { result =>
      result.map { scoredList =>
        scoredList.map { scored =>
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
    }

  def searchKeywordOnly(
    queryText: String,
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[HybridSearchResult]] =
    keywordIndex.searchWithHighlights(queryText, topK, filter = filter).map { result =>
      result.map { keywordList =>
        keywordList.map { ksr =>
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
    }

  private def searchWithRRF(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int,
    k: Int,
    filter: Option[MetadataFilter]
  )(implicit ec: ExecutionContext): AsyncResult[Seq[HybridSearchResult]] = {

    // FIRE CONCURRENTLY: The JVM dispatches these to independent threads immediately
    val vectorFuture  = vectorStore.search(queryEmbedding, topK * 2, filter)
    val keywordFuture = keywordIndex.searchWithHighlights(queryText, topK * 2, filter = filter)

    // Await both futures (non-blocking yield)
    for {
      vEither <- vectorFuture
      kEither <- keywordFuture
    } yield for {
      vectorResults  <- vEither
      keywordResults <- kEither
    } yield {
      val vectorMap  = vectorResults.zipWithIndex.map { case (sr, idx) => sr.record.id -> (idx + 1, sr) }.toMap
      val keywordMap = keywordResults.zipWithIndex.map { case (ksr, idx) => ksr.id -> (idx + 1, ksr) }.toMap

      val allIds = (vectorMap.keySet ++ keywordMap.keySet).toSeq

      allIds
        .map { id =>
          val vectorContrib  = vectorMap.get(id).map { case (rank, _) => 1.0 / (k + rank) }.getOrElse(0.0)
          val keywordContrib = keywordMap.get(id).map { case (rank, _) => 1.0 / (k + rank) }.getOrElse(0.0)

          val vectorData  = vectorMap.get(id).map(_._2)
          val keywordData = keywordMap.get(id).map(_._2)

          HybridSearchResult(
            id = id,
            content = vectorData.flatMap(_.record.content).orElse(keywordData.map(_.content)).getOrElse(""),
            score = vectorContrib + keywordContrib,
            vectorScore = vectorData.map(_.score),
            keywordScore = keywordData.map(_.score),
            metadata = vectorData.map(_.record.metadata).orElse(keywordData.map(_.metadata)).getOrElse(Map.empty),
            highlights = keywordData.map(_.highlights).getOrElse(Seq.empty)
          )
        }
        .sortBy(-_.score)
        .take(topK)
    }
  }

  private def searchWithWeightedScore(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int,
    vectorWeight: Double,
    keywordWeight: Double,
    filter: Option[MetadataFilter]
  )(implicit ec: ExecutionContext): AsyncResult[Seq[HybridSearchResult]] = {

    // FIRE CONCURRENTLY
    val vectorFuture  = vectorStore.search(queryEmbedding, topK * 2, filter)
    val keywordFuture = keywordIndex.searchWithHighlights(queryText, topK * 2, filter = filter)

    for {
      vEither <- vectorFuture
      kEither <- keywordFuture
    } yield for {
      vectorResults  <- vEither
      keywordResults <- kEither
    } yield {
      val vectorScores           = vectorResults.map(_.score)
      val (vectorMin, vectorMax) = if (vectorScores.isEmpty) (0.0, 1.0) else (vectorScores.min, vectorScores.max)

      val keywordScores            = keywordResults.map(_.score)
      val (keywordMin, keywordMax) = if (keywordScores.isEmpty) (0.0, 1.0) else (keywordScores.min, keywordScores.max)

      def normalizeVector(s: Double) = if (vectorMax == vectorMin) 1.0 else (s - vectorMin) / (vectorMax - vectorMin)
      def normalizeKeyword(s: Double) =
        if (keywordMax == keywordMin) 1.0 else (s - keywordMin) / (keywordMax - keywordMin)

      val vectorMap  = vectorResults.map(sr => sr.record.id -> sr).toMap
      val keywordMap = keywordResults.map(ksr => ksr.id -> ksr).toMap

      val allIds      = (vectorMap.keySet ++ keywordMap.keySet).toSeq
      val totalWeight = vectorWeight + keywordWeight

      allIds
        .map { id =>
          val nv = vectorMap.get(id).map(sr => normalizeVector(sr.score)).getOrElse(0.0)
          val nk = keywordMap.get(id).map(ksr => normalizeKeyword(ksr.score)).getOrElse(0.0)

          val vectorData  = vectorMap.get(id)
          val keywordData = keywordMap.get(id)

          HybridSearchResult(
            id = id,
            content = vectorData.flatMap(_.record.content).orElse(keywordData.map(_.content)).getOrElse(""),
            score = (nv * vectorWeight + nk * keywordWeight) / totalWeight,
            vectorScore = vectorData.map(_.score),
            keywordScore = keywordData.map(_.score),
            metadata = vectorData.map(_.record.metadata).orElse(keywordData.map(_.metadata)).getOrElse(Map.empty),
            highlights = keywordData.map(_.highlights).getOrElse(Seq.empty)
          )
        }
        .sortBy(-_.score)
        .take(topK)
    }
  }

  def close(): Unit = {
    vectorStore.close()
    keywordIndex.close()
  }
}

object AsyncHybridSearcher {
  def apply(
    vectorStore: AsyncVectorStore,
    keywordIndex: AsyncKeywordIndex,
    defaultStrategy: FusionStrategy = FusionStrategy.default
  ): AsyncHybridSearcher = new AsyncHybridSearcher(vectorStore, keywordIndex, defaultStrategy)

  def apply(syncSearcher: HybridSearcher): AsyncHybridSearcher =
    new AsyncHybridSearcher(
      AsyncVectorStore(syncSearcher.vectorStore),
      AsyncKeywordIndex(syncSearcher.keywordIndex),
      syncSearcher.defaultStrategy
    )
}
