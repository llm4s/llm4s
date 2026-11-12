package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.provider.CostEstimator
import org.llm4s.model.ModelRegistryTestSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Providers report thinking tokens two ways. OpenAI, and servers that copy its format (Groq,
 * DeepSeek, OpenRouter), count `reasoning_tokens` inside `completion_tokens`, and `total_tokens` is
 * prompt + completion. Gemini (`thoughtsTokenCount`) and xAI count them outside, and the total adds
 * them. `totalOutputTokens`, and the cost built on it, must count each output token once either way.
 * Real usage blocks from 2026-11: Groq `openai/gpt-oss-20b` gave prompt 81, completion 64, total 145,
 * reasoning 46; Gemini `gemini-3.5-flash` gave prompt 8, no candidates, total 539, thoughts 531.
 */
class TokenUsageThinkingSpec extends AnyFlatSpec with Matchers {

  private val registry = ModelRegistryTestSupport.defaultService()

  "totalOutputTokens" should "not add thinking that the completion count already holds (OpenAI's way)" in {
    TokenUsage(81, 64, 145, thinkingTokens = Some(46)).totalOutputTokens shouldBe 64
  }

  it should "add thinking that is counted apart from the completion (Gemini's and xAI's way)" in {
    TokenUsage(8, 0, 539, thinkingTokens = Some(531)).totalOutputTokens shouldBe 531
    TokenUsage(1000, 500, 3500, thinkingTokens = Some(2000)).totalOutputTokens shouldBe 2500
  }

  it should "not add thinking that is the whole completion, as in a reply that only reasoned (OpenAI's way)" in {
    TokenUsage(8, 531, 539, thinkingTokens = Some(531)).totalOutputTokens shouldBe 531
  }

  it should "add thinking smaller than the completion when the total shows it was counted apart (xAI's way)" in {
    TokenUsage(100, 500, 800, thinkingTokens = Some(200)).totalOutputTokens shouldBe 700
  }

  it should "add thinking larger than the completion, which cannot be inside it" in {
    TokenUsage(100, 50, 150, thinkingTokens = Some(200)).totalOutputTokens shouldBe 250
  }

  it should "be the completion when there is no thinking" in {
    TokenUsage(100, 50, 150).totalOutputTokens shouldBe 50
    TokenUsage(100, 50, 150, thinkingTokens = Some(0)).totalOutputTokens shouldBe 50
  }

  "CostEstimator" should "price gpt-5 reasoning once, since OpenAI counts it inside completion_tokens" in {
    // 1000 input at 1.25e-6 and 5000 output at 1e-5, the 4000 reasoning tokens already among the 5000.
    val cost = CostEstimator.estimateFromMetadata(
      registry.lookup("gpt-5").toOption,
      TokenUsage(1000, 5000, 6000, thinkingTokens = Some(4000))
    )
    cost.get shouldBe 0.05125 +- 1e-9
  }

  it should "price Gemini's thoughts on top of its answer, since Gemini counts them apart" in {
    // 1000 input at 3e-7, then 500 answer tokens and 2000 thought tokens at 2.5e-6 each.
    val cost = CostEstimator.estimateFromMetadata(
      registry.lookup("gemini-2.5-flash").toOption,
      TokenUsage(1000, 500, 3500, thinkingTokens = Some(2000))
    )
    cost.get shouldBe 0.00655 +- 1e-9
  }

  it should "price a Gemini reply that only thought, rather than reporting it as free" in {
    // 8 input at 3e-7 and 531 thought tokens at 2.5e-6.
    val cost = CostEstimator.estimateFromMetadata(
      registry.lookup("gemini-2.5-flash").toOption,
      TokenUsage(8, 0, 539, thinkingTokens = Some(531))
    )
    cost.get shouldBe (8 * 3e-7 + 531 * 2.5e-6) +- 1e-9
  }
}
