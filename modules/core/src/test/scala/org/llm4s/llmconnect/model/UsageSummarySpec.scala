package org.llm4s.llmconnect.model

import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

class UsageSummarySpec extends AnyFlatSpec with Matchers {

  "UsageSummary.add" should "accumulate totals and per-model usage immutably" in {
    val s0 = UsageSummary()

    val s1 = s0.add(
      model = ModelName("openai/gpt-4o"),
      usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
      cost = Some(0.01)
    )

    s0.requestCount shouldBe 0L
    s1.requestCount shouldBe 1L
    s1.inputTokens shouldBe 10L
    s1.outputTokens shouldBe 5L
    s1.thinkingTokens shouldBe 0L
    s1.totalCost shouldBe BigDecimal("0.01")

    val mu = s1.byModel(ModelName("openai/gpt-4o"))
    mu.requestCount shouldBe 1L
    mu.inputTokens shouldBe 10L
    mu.outputTokens shouldBe 5L
    mu.thinkingTokens shouldBe 0L
    mu.totalCost shouldBe BigDecimal("0.01")
  }

  it should "not add cost when cost is None" in {
    val s0 = UsageSummary()

    val s1 = s0.add(
      model = ModelName("openai/gpt-4o"),
      usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
      cost = None
    )

    s1.totalCost shouldBe BigDecimal(0)
    s1.byModel(ModelName("openai/gpt-4o")).totalCost shouldBe BigDecimal(0)
  }

  it should "accumulate thinking tokens" in {
    val s0 = UsageSummary()

    val s1 = s0.add(
      model = ModelName("anthropic/claude"),
      usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 23, thinkingTokens = Some(8)),
      cost = Some(0.02)
    )

    s1.thinkingTokens shouldBe 8L
    s1.byModel(ModelName("anthropic/claude")).thinkingTokens shouldBe 8L
  }

  "UsageSummary.merge" should "sum totals and merge byModel" in {
    val left = UsageSummary()
      .add(
        model = ModelName("openai/gpt-4o"),
        usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        cost = Some(0.01)
      )

    val right = UsageSummary()
      .add(
        model = ModelName("openai/gpt-4o"),
        usage = TokenUsage(promptTokens = 3, completionTokens = 7, totalTokens = 10),
        cost = None
      )
      .add(
        model = ModelName("gemini/gemini-2.0-flash"),
        usage = TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3),
        cost = Some(0.005)
      )

    val merged = left.merge(right)

    merged.requestCount shouldBe 3L
    merged.inputTokens shouldBe 14L
    merged.outputTokens shouldBe 14L
    merged.totalCost shouldBe BigDecimal("0.015")

    merged.byModel.keySet shouldBe Set(ModelName("openai/gpt-4o"), ModelName("gemini/gemini-2.0-flash"))

    val openAi = merged.byModel(ModelName("openai/gpt-4o"))
    openAi.requestCount shouldBe 2L
    openAi.inputTokens shouldBe 13L
    openAi.outputTokens shouldBe 12L
    openAi.totalCost shouldBe BigDecimal("0.01")

    val gemini = merged.byModel(ModelName("gemini/gemini-2.0-flash"))
    gemini.requestCount shouldBe 1L
    gemini.inputTokens shouldBe 1L
    gemini.outputTokens shouldBe 2L
    gemini.totalCost shouldBe BigDecimal("0.005")
  }

  // #2807: the cache counters were dropped, so what a prompt cache saved could not be added up
  "UsageSummary.add" should "accumulate cache reads and writes, in total and per model" in {
    val cached = TokenUsage(100, 10, 110, cachedTokens = Some(60), cacheCreationTokens = Some(30))
    val plain  = TokenUsage(40, 5, 45)

    val summary = UsageSummary()
      .add(ModelName("m1"), cached, None)
      .add(ModelName("m1"), cached, None)
      .add(ModelName("m2"), plain, None)

    summary.cachedTokens shouldBe 120L
    summary.cacheCreationTokens shouldBe 60L
    summary.byModel(ModelName("m1")).cachedTokens shouldBe 120L
    summary.byModel(ModelName("m1")).cacheCreationTokens shouldBe 60L
    summary.byModel(ModelName("m2")).cachedTokens shouldBe 0L
    summary.byModel(ModelName("m2")).cacheCreationTokens shouldBe 0L
  }

  "UsageSummary.merge" should "sum the cache counters" in {
    val one =
      UsageSummary().add(
        ModelName("m"),
        TokenUsage(10, 1, 11, cachedTokens = Some(4), cacheCreationTokens = Some(2)),
        None
      )
    val two = UsageSummary().add(ModelName("m"), TokenUsage(10, 1, 11, cachedTokens = Some(3)), None)

    val merged = one.merge(two)
    (merged.cachedTokens, merged.cacheCreationTokens) shouldBe (7L, 2L)
    (merged.byModel(ModelName("m")).cachedTokens, merged.byModel(ModelName("m")).cacheCreationTokens) shouldBe (7L, 2L)
  }

  "UsageSummary JSON" should "read a document written before the cache counters existed" in {
    val stored =
      """{"requestCount":2,"inputTokens":100,"outputTokens":50,"thinkingTokens":10,"totalCost":"0.1234",""" +
        """"byModel":{"test-model":{"requestCount":2,"inputTokens":100,"outputTokens":50,"thinkingTokens":10,""" +
        """"totalCost":"0.1234"}}}"""

    val decoded = read[UsageSummary](stored)

    decoded.requestCount shouldBe 2L
    decoded.totalCost shouldBe BigDecimal("0.1234")
    (decoded.cachedTokens, decoded.cacheCreationTokens) shouldBe (0L, 0L)
    decoded.byModel(ModelName("test-model")).inputTokens shouldBe 100L
    (
      decoded.byModel(ModelName("test-model")).cachedTokens,
      decoded.byModel(ModelName("test-model")).cacheCreationTokens
    ) shouldBe (0L, 0L)
  }

  it should "round-trip the cache counters" in {
    val original = UsageSummary().add(
      ModelName("m"),
      TokenUsage(10, 1, 11, cachedTokens = Some(4), cacheCreationTokens = Some(2)),
      Some(BigDecimal("0.000001"))
    )
    read[UsageSummary](write(original)) shouldBe original
  }

  it should "round-trip correctly" in {
    val original =
      UsageSummary(
        requestCount = 2,
        inputTokens = 100,
        outputTokens = 50,
        thinkingTokens = 10,
        totalCost = BigDecimal("0.1234"),
        byModel = Map(
          ModelName("test-model") ->
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

  "UsageSummary derived metrics" should "compute averages correctly" in {
    val summary = UsageSummary(
      requestCount = 2,
      inputTokens = 100,
      outputTokens = 100,
      totalCost = BigDecimal("0.20")
    )

    summary.averageCostPerRequest shouldBe BigDecimal("0.10")
    summary.averageInputTokensPerRequest shouldBe BigDecimal("50")
    summary.averageOutputTokensPerRequest shouldBe BigDecimal("50")
  }

  it should "return zero averages when requestCount is zero" in {
    val summary = UsageSummary()

    summary.averageCostPerRequest shouldBe BigDecimal(0)
    summary.averageInputTokensPerRequest shouldBe BigDecimal(0)
    summary.averageOutputTokensPerRequest shouldBe BigDecimal(0)
  }

  it should "compute cost per 1K tokens correctly" in {
    val summary = UsageSummary(
      requestCount = 1,
      inputTokens = 500,
      outputTokens = 500,
      totalCost = BigDecimal("0.10")
    )

    summary.costPer1KTokens shouldBe BigDecimal("0.10")
  }

  it should "return zero cost per 1K tokens when total tokens are zero" in {
    val summary = UsageSummary(
      requestCount = 1,
      inputTokens = 0,
      outputTokens = 0,
      totalCost = BigDecimal("0.10")
    )

    summary.costPer1KTokens shouldBe BigDecimal(0)
  }

  it should "produce formatted summary string" in {
    val summary = UsageSummary(
      requestCount = 1,
      inputTokens = 100,
      outputTokens = 50,
      totalCost = BigDecimal("0.01")
    )

    val formatted = summary.formattedSummary

    formatted should include("Requests: 1")
    formatted should include("Input tokens: 100")
    formatted should include("Output tokens: 50")
    formatted should include("Total cost")
  }

  it should "handle unexpected JSON values for BigDecimal deserialization" in {
    val invalidJson =
      """{
        |  "requestCount": 0,
        |  "inputTokens": 0,
        |  "outputTokens": 0,
        |  "thinkingTokens": 0,
        |  "totalCost": true,
        |  "byModel": {}
        |}""".stripMargin

    val decoded = read[UsageSummary](invalidJson)

    decoded.totalCost shouldBe BigDecimal(0)
  }

  it should "compute average cost per request for ModelUsage" in {
    val usage = ModelUsage(
      requestCount = 2,
      inputTokens = 10,
      outputTokens = 10,
      totalCost = BigDecimal("0.20")
    )

    usage.averageCostPerRequest shouldBe BigDecimal("0.10")
  }

  it should "deserialize numeric BigDecimal values correctly" in {
    val numericJson =
      """{
      |  "requestCount": 1,
      |  "inputTokens": 10,
      |  "outputTokens": 5,
      |  "thinkingTokens": 0,
      |  "totalCost": 0.50,
      |  "byModel": {}
      |}""".stripMargin

    val decoded = read[UsageSummary](numericJson)

    decoded.totalCost shouldBe BigDecimal("0.50")
  }
}
