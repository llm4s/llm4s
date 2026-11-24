package org.llm4s.rag

import org.llm4s.error.LLMError
import org.llm4s.rag.evaluation.EvaluationError
import org.llm4s.reranker.RerankError
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pins the `llm4s-rag` row of the "errors defined by other modules" table in
 * `docs/guide/error-handling.md`: these errors are not retryable, so the library's automatic retries leave them
 * alone. If one becomes retryable, update that table and this spec.
 */
class RagErrorMarkersSpec extends AnyWordSpec with Matchers {

  private val errors: List[LLMError] = List(
    EvaluationError("evaluation failed"),
    RerankError(Some("500"), "rerank failed", "cohere")
  )

  "llm4s-rag's own errors" should {
    "not be retryable, as the error handling guide lists them" in {
      errors.foreach(error => withClue(error.getClass.getSimpleName)(error.isRetryable shouldBe false))
      errors.filter(_.isRetryable) shouldBe empty
    }
  }
}
