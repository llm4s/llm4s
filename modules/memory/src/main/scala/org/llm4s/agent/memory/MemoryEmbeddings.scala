package org.llm4s.agent.memory

import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.llm4s.vectorstore.SearchScores
import org.slf4j.LoggerFactory

import scala.util.Try

/** How a store with an [[EmbeddingService]] embeds what it writes, shared by every such store. */
private[memory] object MemoryEmbeddings {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * `memories` with every missing embedding filled in, asking `service` for at most `batchSize` texts at a time - one
   * request for a whole large batch could exceed a provider's input limit. Fails if any call fails, throws, or answers
   * with the wrong number of embeddings; `component` names the store in the error.
   */
  def embedMissing(
    service: EmbeddingService,
    memories: Seq[Memory],
    component: String,
    batchSize: Int = VectorMemoryStore.EmbeddingBatchSize
  ): Result[Seq[Memory]] = {
    val texts = memories.filter(_.embedding.isEmpty).map(_.content)
    val embedded =
      texts.grouped(batchSize).foldLeft[Result[Vector[Array[Float]]]](Right(Vector.empty)) { (acc, chunk) =>
        acc.flatMap { done =>
          Try(service.embedBatch(chunk))
            .fold(
              e => Left(ProcessingError(component, s"Embedding failed: ${Redaction.scrubKnown(e.getMessage)}")),
              identity
            )
            .flatMap { embeddings =>
              if (embeddings.size == chunk.size) Right(done ++ embeddings)
              else
                Left(
                  ProcessingError(
                    component,
                    s"Embedding service returned ${embeddings.size} embeddings for ${chunk.size} texts"
                  )
                )
            }
        }
      }
    embedded.map { embeddings =>
      val next = embeddings.iterator
      memories.map(m => if (m.embedding.isEmpty) m.withEmbedding(next.next()) else m)
    }
  }

  /**
   * What `update` stores: `updated` as the update function returned it, except that when the content changed and the
   * embedding was left as it was - computed for the old content - the embedding is dropped, for the store to compute
   * again.
   */
  def afterUpdate(existing: Memory, updated: Memory): Memory =
    if (updated.content != existing.content && updated.embedding == existing.embedding) updated.copy(embedding = None)
    else updated

  /**
   * `candidates` - memories and their non-empty embeddings - scored against `query` by the shared rule
   * (`SearchScores`), unsorted. A candidate whose embedding cannot be compared with the query (another dimension, all
   * zeros, a non-finite component) is left out; a query that cannot be compared with anything leaves out every one.
   *
   * An empty query compares with nothing, so it finds nothing. Fails when there are candidates but none has the
   * query's dimension: the store was written with another embedding model, and an empty result or a keyword fallback
   * would hide that. Candidates of another dimension beside some of the query's are skipped and counted in the log.
   */
  def rank(query: Array[Float], candidates: Seq[(Memory, Array[Float])]): Result[Seq[ScoredMemory]] =
    rankIds(query, candidates.map { case (m, v) => (m, v) }, (m: Memory) => m.id).map(_.map { case (m, score) =>
      ScoredMemory(m, score)
    })

  /** [[rank]] over any candidates, identified by `idOf` in a mismatch's error; scores, unsorted. */
  def rankIds(query: Array[Float], candidates: Seq[(MemoryId, Array[Float])]): Result[Seq[(MemoryId, Double)]] =
    rankIds(query, candidates, identity[MemoryId])

  def rankIds[A](
    query: Array[Float],
    candidates: Seq[(A, Array[Float])],
    idOf: A => MemoryId
  ): Result[Seq[(A, Double)]] =
    if (query.isEmpty) Right(Seq.empty)
    else {
      val (comparable, mismatched) = candidates.partition(_._2.length == query.length)
      mismatched.headOption match {
        case Some((candidate, stored)) if comparable.isEmpty =>
          Left(dimensionMismatch(query.length, idOf(candidate), stored.length))
        case _ =>
          if (mismatched.nonEmpty)
            logger.warn(
              s"Vector search skipped ${mismatched.size} memories whose embedding has a different dimension than " +
                s"the query's ${query.length}; re-embed the store to include them"
            )
          Right(comparable.flatMap { case (candidate, embedding) =>
            SearchScores.cosine(query, embedding).flatMap(SearchScores.fromCosine).map(candidate -> _)
          })
      }
    }

  /** The error of a search none of whose candidates has the query's dimension. */
  def dimensionMismatch(queryDimension: Int, memory: MemoryId, storedDimension: Int): ConfigurationError =
    ConfigurationError(
      s"Embedding dimension mismatch: the query embedding has $queryDimension dimensions but memory ${memory.value} " +
        s"was stored with $storedDimension. The store was written with a different embedding model; use that model, " +
        "or re-embed the store."
    )
}
