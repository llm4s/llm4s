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

  /**
   * Embeds text by term overlap, so that similar text produces nearby vectors.
   *
   * [[DeterministicMock]] seeds an RNG from the whole string's hash code, which makes every
   * vector reproducible but unrelated to every other - two texts sharing every word but one
   * are as far apart as two texts sharing nothing. That is enough to test that a pipeline
   * runs, and useless for testing that it retrieves the right thing, which is why tests
   * built on it can only assert that results are non-empty.
   *
   * This mock uses the hashing trick: each token increments the component
   * `hash(token) mod dimensions`, and the vector is L2-normalised. Cosine similarity then
   * tracks how many terms two texts share, so a query about "functional programming" really
   * does rank a document about functional programming above one about databases - and a
   * retrieval test can assert which document came back rather than merely that one did.
   *
   * Deterministic and order-independent: no state, no dependence on ingestion order.
   */
  class BagOfWordsMock(dimensions: Int = 64) extends EmbeddingProvider {
    var callCount: Int = 0

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      callCount += 1
      Right(
        EmbeddingResponse(
          embeddings = request.input.map(BagOfWordsMock.vectorFor(_, dimensions)),
          metadata = Map("provider" -> "bag-of-words-mock")
        )
      )
    }
  }

  object BagOfWordsMock {

    /** The vector this mock produces for `text`; exposed so tests can assert on similarity. */
    def vectorFor(text: String, dimensions: Int = 64): Seq[Double] = {
      val counts = Array.fill(dimensions)(0.0)
      tokenize(text).foreach { token =>
        // `.abs` on Int.MinValue is still negative, so mask the sign bit instead.
        val bucket = (token.hashCode & 0x7fffffff) % dimensions
        counts(bucket) += 1.0
      }
      val norm = math.sqrt(counts.map(x => x * x).sum)
      if (norm == 0.0) counts.toSeq else counts.map(_ / norm).toSeq
    }

    private def tokenize(text: String): Seq[String] =
      text.toLowerCase.split("[^a-z0-9]+").toSeq.filter(_.length > 2)
  }

  /** Always returns Left(EmbeddingError(...)). */
  class FailingMock(errorMessage: String = "Mock embedding error") extends EmbeddingProvider {

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Left(EmbeddingError(Some("500"), errorMessage, "failing-mock"))
  }
}
