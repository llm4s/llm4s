package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Each `CompletionOptions` setter changes its own field and nothing else. */
class CompletionOptionsSettersSpec extends AnyFlatSpec with Matchers {

  private val base = CompletionOptions()

  "CompletionOptions setters" should "set the sampling fields" in {
    base.withTemperature(0.1) shouldBe CompletionOptions(temperature = 0.1)
    base.withTopP(0.5) shouldBe CompletionOptions(topP = 0.5)
    base.withPresencePenalty(0.3) shouldBe CompletionOptions(presencePenalty = 0.3)
    base.withFrequencyPenalty(0.4) shouldBe CompletionOptions(frequencyPenalty = 0.4)
  }

  it should "set and clear maxTokens" in {
    base.withMaxTokens(100).maxTokens shouldBe Some(100)
    base.withMaxTokens(Some(200)).maxTokens shouldBe Some(200)
    base.withMaxTokens(100).withMaxTokens(None) shouldBe base
  }

  it should "set the tools" in {
    base.withTools(Seq.empty).tools shouldBe empty
    base.withTools(Seq.empty) shouldBe base
  }

  it should "set and clear reasoning, the thinking budget and the response format" in {
    base.withReasoning(Some(ReasoningEffort.High)).reasoning shouldBe Some(ReasoningEffort.High)
    base.withReasoning(Some(ReasoningEffort.High)).withReasoning(None) shouldBe base
    base.withBudgetTokens(Some(1000)).budgetTokens shouldBe Some(1000)
    base.withBudgetTokens(Some(1000)).withBudgetTokens(None) shouldBe base
    base.withResponseFormat(Some(ResponseFormat.Json)).responseFormat shouldBe Some(ResponseFormat.Json)
    base.withResponseFormat(Some(ResponseFormat.Json)).withResponseFormat(None) shouldBe base
  }
}
