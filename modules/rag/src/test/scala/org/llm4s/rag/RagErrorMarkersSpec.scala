package org.llm4s.rag

import org.llm4s.error.{ LLMError, NonRecoverableError, RecoverableError }
import org.llm4s.rag.evaluation.EvaluationError
import org.llm4s.reranker.RerankError
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pins the `llm4s-rag` row of the "errors defined by other modules" table in
 * `docs/guide/error-handling.md`: these errors carry neither recoverability marker, so
 * `LLMError.isRecoverable` cannot classify them and the guide's marker-trait match must be used.
 * If one gains a marker, move it out of that table and update this spec.
 */
class RagErrorMarkersSpec extends AnyWordSpec with Matchers {

  private val unmarked: List[LLMError] = List(
    EvaluationError("evaluation failed"),
    RerankError(Some("500"), "rerank failed", "cohere")
  )

  private def retryable(error: LLMError): Boolean = error match {
    case _: RecoverableError => true
    case _                   => false
  }

  "llm4s-rag's own errors" should {
    "carry neither recoverability marker, as the error handling guide lists them" in {
      unmarked.foreach { error =>
        withClue(error.getClass.getSimpleName) {
          error should not be a[RecoverableError]
          error should not be a[NonRecoverableError]
        }
      }
    }

    "be classified safely by the guide's marker-trait match" in {
      unmarked.map(retryable).distinct shouldBe List(false)
    }
  }
}
