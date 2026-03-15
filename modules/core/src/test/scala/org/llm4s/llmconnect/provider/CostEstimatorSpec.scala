package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.model.{ ModelMetadata, ModelPricing, ModelCapabilities, ModelMode }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CostEstimatorSpec extends AnyFlatSpec with Matchers {

  behavior.of("CostEstimator")

  private def assertBigDecimalClose(actual: Double, expected: BigDecimal, tolerance: BigDecimal): Unit = {
    val actualBd = BigDecimal.decimal(actual)
    (actualBd - expected).abs should be <= tolerance
  }

  val sampleUsage = TokenUsage(
    promptTokens = 100,
    completionTokens = 50,
    totalTokens = 150
  )

  val sampleUsageWithThinking = TokenUsage(
    promptTokens = 100,
    completionTokens = 50,
    totalTokens = 200,
    thinkingTokens = Some(50)
  )

  val pricedMetadata = ModelMetadata(
    modelId = "test-model",
    provider = "test-provider",
    mode = ModelMode.Chat,
    maxInputTokens = Some(4096),
    maxOutputTokens = Some(4096),
    inputCostPerToken = Some(0.00001),
    outputCostPerToken = Some(0.00002),
    capabilities = ModelCapabilities(),
    pricing = ModelPricing(
      inputCostPerToken = Some(0.00001),
      outputCostPerToken = Some(0.00002)
    ),
    deprecationDate = None
  )

  val unpricedMetadata = ModelMetadata(
    modelId = "local-model",
    provider = "ollama",
    mode = ModelMode.Chat,
    maxInputTokens = Some(4096),
    maxOutputTokens = Some(4096),
    inputCostPerToken = None,
    outputCostPerToken = None,
    capabilities = ModelCapabilities(),
    pricing = ModelPricing(),
    deprecationDate = None
  )

  it should "estimate cost from metadata with standard usage" in {
    val cost = CostEstimator.estimateFromMetadata(Some(pricedMetadata), sampleUsage)

    cost shouldBe defined
    // 100 * 0.00001 + 50 * 0.00002 = 0.001 + 0.001 = 0.002
    cost.get shouldBe 0.002 +- 0.0001
  }

  it should "estimate cost from metadata with thinking tokens" in {
    val cost = CostEstimator.estimateFromMetadata(Some(pricedMetadata), sampleUsageWithThinking)

    cost shouldBe defined
    // 100 * 0.00001 + (50 + 50) * 0.00002 = 0.001 + 0.002 = 0.003
    cost.get shouldBe 0.003 +- 0.0001
  }

  it should "return None when metadata is missing" in {
    val cost = CostEstimator.estimateFromMetadata(None, sampleUsage)
    cost shouldBe None
  }

  it should "return None when pricing is unavailable" in {
    val cost = CostEstimator.estimateFromMetadata(Some(unpricedMetadata), sampleUsage)
    cost shouldBe None
  }

  it should "estimate cost directly with provided pricing" in {
    val cost = CostEstimator.estimateDirect(0.00001, 0.00002, sampleUsage)

    // 100 * 0.00001 + 50 * 0.00002 = 0.002
    cost shouldBe 0.002 +- 0.0001
  }

  it should "preserve legacy behavior when cache breakdown is absent" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(0.01),
      outputCostPerToken = Some(0.02),
      cacheReadInputTokenCost = Some(0.005),
      cacheCreationInputTokenCost = Some(0.007)
    )

    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = pricing.inputCostPerToken,
      outputCostPerToken = pricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150
    )

    val cost = CostEstimator.estimateFromMetadata(Some(metadata), usage)
    cost shouldBe defined

    val expected = BigDecimal(100) * BigDecimal("0.01") + BigDecimal(50) * BigDecimal("0.02")
    assertBigDecimalClose(cost.get, expected, tolerance = BigDecimal("0.000000001"))
  }

  it should "price cache read tokens separately" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(0.01),
      outputCostPerToken = Some(0.02),
      cacheReadInputTokenCost = Some(0.005),
      cacheCreationInputTokenCost = Some(0.007)
    )

    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = pricing.inputCostPerToken,
      outputCostPerToken = pricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150,
      cachedTokens = Some(30)
    )

    val cost = CostEstimator.estimateFromMetadata(Some(metadata), usage)
    cost shouldBe defined

    val expected =
      BigDecimal(70) * BigDecimal("0.01") +
        BigDecimal(30) * BigDecimal("0.005") +
        BigDecimal(50) * BigDecimal("0.02")

    assertBigDecimalClose(cost.get, expected, tolerance = BigDecimal("0.000000001"))
  }

  it should "price cache creation tokens separately" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(0.01),
      outputCostPerToken = Some(0.02),
      cacheReadInputTokenCost = Some(0.005),
      cacheCreationInputTokenCost = Some(0.007)
    )

    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = pricing.inputCostPerToken,
      outputCostPerToken = pricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150,
      cacheCreationTokens = Some(20)
    )

    val cost = CostEstimator.estimateFromMetadata(Some(metadata), usage)
    cost shouldBe defined

    val expected =
      BigDecimal(80) * BigDecimal("0.01") +
        BigDecimal(20) * BigDecimal("0.007") +
        BigDecimal(50) * BigDecimal("0.02")

    assertBigDecimalClose(cost.get, expected, tolerance = BigDecimal("0.000000001"))
  }

  it should "price cache read and cache creation tokens separately when both are present" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(0.01),
      outputCostPerToken = Some(0.02),
      cacheReadInputTokenCost = Some(0.005),
      cacheCreationInputTokenCost = Some(0.007)
    )

    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = pricing.inputCostPerToken,
      outputCostPerToken = pricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

    val usage = TokenUsage(
      promptTokens = 100,
      completionTokens = 50,
      totalTokens = 150,
      cachedTokens = Some(20),
      cacheCreationTokens = Some(10)
    )

    val cost = CostEstimator.estimateFromMetadata(Some(metadata), usage)
    cost shouldBe defined

    val expected =
      BigDecimal(70) * BigDecimal("0.01") +
        BigDecimal(20) * BigDecimal("0.005") +
        BigDecimal(10) * BigDecimal("0.007") +
        BigDecimal(50) * BigDecimal("0.02")

    assertBigDecimalClose(cost.get, expected, tolerance = BigDecimal("0.000000001"))
  }

  it should "clamp normal input tokens to zero when cache token totals exceed prompt tokens" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(0.01),
      outputCostPerToken = Some(0.02),
      cacheReadInputTokenCost = Some(0.005),
      cacheCreationInputTokenCost = Some(0.007)
    )

    val metadata = ModelMetadata(
      modelId = "test-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = pricing.inputCostPerToken,
      outputCostPerToken = pricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

    val usage = TokenUsage(
      promptTokens = 50,
      completionTokens = 50,
      totalTokens = 100,
      cachedTokens = Some(40),
      cacheCreationTokens = Some(30)
    )

    val cost = CostEstimator.estimateFromMetadata(Some(metadata), usage)
    cost shouldBe defined

    val expected =
      BigDecimal(0) * BigDecimal("0.01") +
        BigDecimal(40) * BigDecimal("0.005") +
        BigDecimal(30) * BigDecimal("0.007") +
        BigDecimal(50) * BigDecimal("0.02")

    assertBigDecimalClose(cost.get, expected, tolerance = BigDecimal("0.000000001"))
  }

  it should "bill reasoning tokens separately without double counting" in {

    val pricingWithReasoning = ModelPricing(
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.000002),
      outputCostPerReasoningToken = Some(0.000004)
    )

    val metadataWithReasoning = ModelMetadata(
      modelId = "reasoning-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.000002),
      capabilities = ModelCapabilities(),
      pricing = pricingWithReasoning,
      deprecationDate = None
    )

    val usage = TokenUsage(
      promptTokens = 1000,
      completionTokens = 500,
      totalTokens = 1500,
      thinkingTokens = Some(200)
    )

    val cost = CostEstimator.estimateFromMetadata(Some(metadataWithReasoning), usage)

    cost shouldBe defined

    // Expected:
    // input = 1000 * 0.000001 = 0.001
    // normalCompletion = 500 - 200 = 300
    // normalOutput = 300 * 0.000002 = 0.0006
    // reasoningOutput = 200 * 0.000004 = 0.0008
    // total = 0.0024

    cost.get shouldBe 0.0024 +- 1e-9
  }

  it should "preserve precision of micro-cost values" in {
    val precisePricing = ModelMetadata(
      modelId = "precise-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.000002),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(
        inputCostPerToken = Some(0.000001),
        outputCostPerToken = Some(0.000002)
      ),
      deprecationDate = None
    )

    val usage = TokenUsage(10, 5, 15)
    val cost  = CostEstimator.estimateFromMetadata(Some(precisePricing), usage)

    cost shouldBe defined
    // 10 * 0.000001 + 5 * 0.000002 = 0.00001 + 0.00001 = 0.00002
    cost.get shouldBe 0.00002 +- 0.000001
  }

  it should "handle zero tokens" in {
    val usage = TokenUsage(0, 0, 0)
    val cost  = CostEstimator.estimateFromMetadata(Some(pricedMetadata), usage)

    cost shouldBe defined
    cost.get shouldBe 0.0
  }

  it should "handle large token counts" in {
    val largeUsage = TokenUsage(100000, 50000, 150000)
    val cost       = CostEstimator.estimateFromMetadata(Some(pricedMetadata), largeUsage)

    cost shouldBe defined
    // 100000 * 0.00001 + 50000 * 0.00002 = 1.0 + 1.0 = 2.0
    cost.get shouldBe 2.0 +- 0.01
  }

  behavior.of("Completion with estimatedCost")

  it should "create Completion with estimated cost" in {
    val completion = Completion(
      id = "test-id",
      created = System.currentTimeMillis() / 1000,
      content = "test content",
      model = "test-model",
      message = org.llm4s.llmconnect.model.AssistantMessage("test content"),
      usage = Some(sampleUsage),
      estimatedCost = Some(0.002)
    )

    completion.estimatedCost shouldBe defined
    completion.estimatedCost.get shouldBe 0.002
  }

  it should "create Completion without estimated cost (backward compatibility)" in {
    val completion = Completion(
      id = "test-id",
      created = System.currentTimeMillis() / 1000,
      content = "test content",
      model = "test-model",
      message = org.llm4s.llmconnect.model.AssistantMessage("test content"),
      usage = Some(sampleUsage)
    )

    completion.estimatedCost shouldBe None
  }

  it should "handle None for estimatedCost by default" in {
    val completion = Completion(
      id = "test-id",
      created = System.currentTimeMillis() / 1000,
      content = "test content",
      model = "test-model",
      message = org.llm4s.llmconnect.model.AssistantMessage("test content")
    )

    completion.estimatedCost shouldBe None
  }
}
