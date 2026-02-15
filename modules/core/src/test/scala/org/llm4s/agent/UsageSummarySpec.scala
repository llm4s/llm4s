package org.llm4s.agent

import org.llm4s.llmconnect.model.TokenUsage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

class UsageSummarySpec extends AnyFlatSpec with Matchers {

  "UsageSummary.add" should "accumulate totals and per-model usage immutably" in {
    val s0 = UsageSummary()

    val s1 = s0.add(
      model = "openai/gpt-4o",
      usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
      cost = Some(0.01)
    )

    s0.requestCount shouldBe 0L
    s1.requestCount shouldBe 1L
    s1.inputTokens shouldBe 10L
    s1.outputTokens shouldBe 5L
    s1.thinkingTokens shouldBe 0L
    s1.totalCost shouldBe BigDecimal("0.01")

    val mu = s1.byModel("openai/gpt-4o")
    mu.requestCount shouldBe 1L
    mu.inputTokens shouldBe 10L
    mu.outputTokens shouldBe 5L
    mu.thinkingTokens shouldBe 0L
    mu.totalCost shouldBe BigDecimal("0.01")
  }

  it should "not add cost when cost is None" in {
    val s0 = UsageSummary()

    val s1 = s0.add(
      model = "openai/gpt-4o",
      usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
      cost = None
    )

    s1.totalCost shouldBe BigDecimal(0)
    s1.byModel("openai/gpt-4o").totalCost shouldBe BigDecimal(0)
  }

  it should "accumulate thinking tokens" in {
    val s0 = UsageSummary()

    val s1 = s0.add(
      model = "anthropic/claude",
      usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 23, thinkingTokens = Some(8)),
      cost = Some(0.02)
    )

    s1.thinkingTokens shouldBe 8L
    s1.byModel("anthropic/claude").thinkingTokens shouldBe 8L
  }

  "UsageSummary.merge" should "sum totals and merge byModel" in {
    val left = UsageSummary()
      .add(
        model = "openai/gpt-4o",
        usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        cost = Some(0.01)
      )

    val right = UsageSummary()
      .add(
        model = "openai/gpt-4o",
        usage = TokenUsage(promptTokens = 3, completionTokens = 7, totalTokens = 10),
        cost = None
      )
      .add(
        model = "gemini/gemini-2.0-flash",
        usage = TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3),
        cost = Some(0.005)
      )

    val merged = left.merge(right)

    merged.requestCount shouldBe 3L
    merged.inputTokens shouldBe 14L
    merged.outputTokens shouldBe 14L
    merged.totalCost shouldBe BigDecimal("0.015")

    merged.byModel.keySet shouldBe Set("openai/gpt-4o", "gemini/gemini-2.0-flash")

    val openAi = merged.byModel("openai/gpt-4o")
    openAi.requestCount shouldBe 2L
    openAi.inputTokens shouldBe 13L
    openAi.outputTokens shouldBe 12L
    openAi.totalCost shouldBe BigDecimal("0.01")

    val gemini = merged.byModel("gemini/gemini-2.0-flash")
    gemini.requestCount shouldBe 1L
    gemini.inputTokens shouldBe 1L
    gemini.outputTokens shouldBe 2L
    gemini.totalCost shouldBe BigDecimal("0.005")
  }

  "UsageSummary JSON" should "round-trip correctly" in {
    val original =
      UsageSummary(
        requestCount = 2,
        inputTokens = 100,
        outputTokens = 50,
        thinkingTokens = 10,
        totalCost = BigDecimal("0.1234"),
        byModel = Map(
          "test-model" ->
            ModelUsage(
              requestCount = 2,
              inputTokens = 100,
              outputTokens = 50,
              thinkingTokens = 10,
              totalCost = BigDecimal("0.1234")
            )
        )
      )

    val json    = write(original)
    val decoded = read[UsageSummary](json)

    decoded shouldBe original
  }
}
