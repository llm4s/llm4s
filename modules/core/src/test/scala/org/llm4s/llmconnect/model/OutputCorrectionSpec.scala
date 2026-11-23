package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The structured-output correction request: the text the agent runtime sends and the testkit recognises (#2730). */
class OutputCorrectionSpec extends AnyFlatSpec with Matchers {

  "OutputCorrection.message" should "list the problems after the prefix and ask for the answer again" in {
    OutputCorrection.message(Seq("$.total: expected a number", "$.customer: missing")) shouldBe
      "Your answer did not match the required JSON Schema:\n" +
      "- $.total: expected a number\n" +
      "- $.customer: missing\n" +
      "Reply again with only a JSON value that matches the schema."
  }

  "OutputCorrection.isCorrection" should "recognise a user message built from message, whatever its problems" in {
    OutputCorrection.isCorrection(UserMessage(OutputCorrection.message(Seq("$: the answer is not JSON")))) shouldBe true
    OutputCorrection.isCorrection(UserMessage(OutputCorrection.message(Seq.empty))) shouldBe true
  }

  it should "not recognise another user message, or the same text from another role" in {
    OutputCorrection.isCorrection(UserMessage("bill Acme")) shouldBe false
    OutputCorrection.isCorrection(UserMessage("Note: " + OutputCorrection.Prefix)) shouldBe false
    OutputCorrection.isCorrection(AssistantMessage(OutputCorrection.message(Seq("x")))) shouldBe false
    OutputCorrection.isCorrection(SystemMessage(OutputCorrection.message(Seq("x")))) shouldBe false
  }
}
