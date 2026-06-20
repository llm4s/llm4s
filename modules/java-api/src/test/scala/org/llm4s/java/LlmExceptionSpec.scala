package org.llm4s.java

import org.llm4s.error.{ LLMError, ValidationError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LlmExceptionSpec extends AnyFlatSpec with Matchers {

  private val error: LLMError = ValidationError("something went wrong", "field")

  "LlmException" should "expose the underlying LLMError" in {
    val ex = new LlmException(error)
    ex.error shouldBe error
  }

  it should "use the LLMError message as the exception message" in {
    val ex = new LlmException(error)
    ex.getMessage shouldBe error.message
  }

  it should "be a RuntimeException" in {
    val ex = new LlmException(error)
    ex shouldBe a[RuntimeException]
  }

  it should "be throwable and catchable" in {
    val caught = intercept[LlmException] {
      throw new LlmException(error)
    }
    caught.error shouldBe error
  }
}
