package org.llm4s.llmconnect.model

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.provider.CostEstimator
import org.llm4s.model.ModelRegistryTestSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `TokenUsage.thinkingTokens` are never part of `completionTokens`. Providers that count reasoning
 * inside their completion count (OpenAI, and servers that copy its format) have it subtracted by
 * their client; Gemini (`thoughtsTokenCount`) and xAI count it apart already. So
 * `totalOutputTokens` is a plain sum, and the cost built on it counts each output token once. Each
 * client's conversion is tested in its own module.
 */
class TokenUsageThinkingSpec extends AnyFlatSpec with Matchers {

  private val registry = ModelRegistryTestSupport.defaultService()

  "totalOutputTokens" should "add thinking to the completion, which never includes it" in {
    // OpenAI's prompt 81, completion 64 (46 of them reasoning), total 145, after its client's conversion
    TokenUsage(81, 18, 145, thinkingTokens = Some(46)).totalOutputTokens shouldBe 64
    // Gemini: no answer tokens, 531 thoughts
    TokenUsage(8, 0, 539, thinkingTokens = Some(531)).totalOutputTokens shouldBe 531
    TokenUsage(1000, 500, 3500, thinkingTokens = Some(2000)).totalOutputTokens shouldBe 2500
  }

  it should "be the completion when there is no thinking" in {
    TokenUsage(100, 50, 150).totalOutputTokens shouldBe 50
    TokenUsage(100, 50, 150, thinkingTokens = Some(0)).totalOutputTokens shouldBe 50
  }

  "CostEstimator" should "price gpt-5 reasoning once" in {
    // OpenAI reported 5000 completion tokens, 4000 of them reasoning; its client keeps 1000 as the completion.
    // 1000 input at 1.25e-6 and 5000 output at 1e-5.
    val cost = CostEstimator.estimateFromMetadata(
      registry.lookup(ModelName("gpt-5")).toOption,
      TokenUsage(1000, 1000, 6000, thinkingTokens = Some(4000))
    )
    cost.get.toDouble shouldBe 0.05125 +- 1e-9
  }

  it should "price Gemini's thoughts on top of its answer" in {
    // 1000 input at 3e-7, then 500 answer tokens and 2000 thought tokens at 2.5e-6 each.
    val cost = CostEstimator.estimateFromMetadata(
      registry.lookup(ModelName("gemini-2.5-flash")).toOption,
      TokenUsage(1000, 500, 3500, thinkingTokens = Some(2000))
    )
    cost.get.toDouble shouldBe 0.00655 +- 1e-9
  }

  it should "price a Gemini reply that only thought, rather than reporting it as free" in {
    // 8 input at 3e-7 and 531 thought tokens at 2.5e-6.
    val cost = CostEstimator.estimateFromMetadata(
      registry.lookup(ModelName("gemini-2.5-flash")).toOption,
      TokenUsage(8, 0, 539, thinkingTokens = Some(531))
    )
    cost.get.toDouble shouldBe (8 * 3e-7 + 531 * 2.5e-6) +- 1e-9
  }

}
