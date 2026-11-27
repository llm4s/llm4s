package org.llm4s.agent.memory

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import scala.util.Try

/** How a store with an [[EmbeddingService]] embeds what it writes, shared by every such store. */
private[memory] object MemoryEmbeddings {

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
}
