package org.llm4s.llmconnect.model

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ThinkingSpec extends AnyFlatSpec with Matchers {

  /** upickle wraps a reader's exception: its reason is on the cause. */
  private def messages(error: Throwable): String =
    Iterator.iterate(error)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString(" ")

  private def tokensOf(thinking: Thinking): Int = thinking match {
    case Thinking.Budget(tokens) => tokens
    case other                   => fail(s"not a budget: $other")
  }

  "Thinking.budget" should "make a budget of a positive number of tokens" in {
    Thinking.budget(1).map(tokensOf) shouldBe Right(1)
    Thinking.budget(8000).map(tokensOf) shouldBe Right(8000)
  }

  it should "refuse zero and negative budgets, naming Off and Adaptive" in {
    for (tokens <- List(0, -1, Int.MinValue)) {
      val error = Thinking.budget(tokens).left.toOption.get
      error shouldBe a[ValidationError]
      error.message should (include("Thinking.Off").and(include("Thinking.Adaptive")))
    }
  }

  it should "give equal budgets of equal tokens" in {
    Thinking.budget(2048) shouldBe Thinking.budget(2048)
    Thinking.budget(2048) should not be Thinking.budget(4096)
  }

  "Thinking's JSON" should "write off, adaptive and a budget, and read them back" in {
    val budget = Thinking.budget(4096).toOption.get
    upickle.default.write[Thinking](Thinking.Off) shouldBe "\"off\""
    upickle.default.write[Thinking](Thinking.Adaptive) shouldBe "\"adaptive\""
    upickle.default.write[Thinking](budget) shouldBe """{"budget":4096}"""
    for (thinking <- List(Thinking.Off, Thinking.Adaptive, budget))
      upickle.default.read[Thinking](upickle.default.write(thinking)) shouldBe thinking
  }

  it should "refuse a stored budget that is not positive" in {
    val error = intercept[Exception](upickle.default.read[Thinking]("""{"budget":0}"""))
    messages(error) should include("must be positive")
  }

  it should "refuse anything else" in {
    for (json <- List("\"enabled\"", """{"budget":"8000"}""", """{"budget":1.5}""", """{"budget":1,"x":2}""", "3"))
      an[Exception] should be thrownBy upickle.default.read[Thinking](json)
  }
}
