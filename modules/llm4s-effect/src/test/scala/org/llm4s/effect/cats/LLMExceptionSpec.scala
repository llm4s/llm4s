package org.llm4s.effect.cats

import org.llm4s.error.SimpleError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMExceptionSpec extends AnyFlatSpec with Matchers {

  "LLMException" should "wrap an LLMError and expose it via .error" in {
    val error = SimpleError("something went wrong")
    val ex    = new LLMException(error)
    ex.error shouldBe error
  }

  it should "carry the LLMError message as its exception message" in {
    val ex = new LLMException(SimpleError("provider timeout"))
    ex.getMessage shouldBe "provider timeout"
  }

  it should "be a RuntimeException" in {
    val ex = new LLMException(SimpleError("test"))
    ex shouldBe a[RuntimeException]
  }
}
