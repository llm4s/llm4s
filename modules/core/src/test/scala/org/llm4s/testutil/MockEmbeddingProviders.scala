package org.llm4s.testutil

import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result

/** Shared mock embedding providers for testing. */
object MockEmbeddingProviders {

  /** Returns fixed-dimension vectors filled with a constant value. */
  class SimpleMock(dimensions: Int, fillValue: Double = 1.0) extends EmbeddingProvider {
    var lastRequest: Option[EmbeddingRequest] = None
    var callCount: Int                        = 0

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      lastRequest = Some(request)
      callCount += 1
      val vectors = request.input.map(_ => Seq.fill(dimensions)(fillValue))
      Right(
        EmbeddingResponse(
          embeddings = vectors,
          metadata = Map("provider" -> "simple-mock")
        )
      )
    }
  }

  /** Returns distinct deterministic vectors per input text (hashCode-based seed). */
  class DeterministicMock(dimensions: Int) extends EmbeddingProvider {

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val vectors = request.input.map { text =>
        val rng = new scala.util.Random(text.hashCode.toLong)
        Seq.fill(dimensions)(rng.nextDouble())
      }
      Right(
        EmbeddingResponse(
          embeddings = vectors,
          metadata = Map("provider" -> "deterministic-mock")
        )
      )
    }
  }

  /** Always returns Left(EmbeddingError(...)). */
  class FailingMock(errorMessage: String = "Mock embedding error") extends EmbeddingProvider {

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Left(EmbeddingError(Some("500"), errorMessage, "failing-mock"))
  }
}
