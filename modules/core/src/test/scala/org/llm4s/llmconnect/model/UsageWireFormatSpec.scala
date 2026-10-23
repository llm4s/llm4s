package org.llm4s.llmconnect.model

import org.llm4s.model.ModelPricing
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * Pins the JSON of the macroRW-serialised growth-prone types byte for byte (#2356): a private constructor must not
 * change what is written, and a document that omits a field must still read, filled from the constructor default.
 */
class UsageWireFormatSpec extends AnyFlatSpec with Matchers {

  private val modelUsage =
    ModelUsage(
      requestCount = 2,
      inputTokens = 100,
      outputTokens = 50,
      thinkingTokens = 10,
      totalCost = BigDecimal("0.1234")
    )

  private val modelUsageJson =
    """{"requestCount":2,"inputTokens":100,"outputTokens":50,"thinkingTokens":10,"totalCost":"0.1234"}"""

  private val summary = UsageSummary(
    requestCount = 3,
    inputTokens = 120,
    outputTokens = 60,
    thinkingTokens = 10,
    totalCost = BigDecimal("0.5"),
    byModel = Map("gpt-4o" -> modelUsage)
  )

  private val summaryJson =
    """{"requestCount":3,"inputTokens":120,"outputTokens":60,"thinkingTokens":10,"totalCost":"0.5",""" +
      s""""byModel":{"gpt-4o":$modelUsageJson}}"""

  private val pricing = ModelPricing(inputCostPerToken = Some(0.001), outputCostPerToken = Some(0.002))

  "ModelUsage JSON" should "be unchanged" in {
    write(modelUsage) shouldBe modelUsageJson
    write(ModelUsage()) shouldBe "{}"
  }

  it should "round-trip, and fill omitted fields from the defaults" in {
    read[ModelUsage](modelUsageJson) shouldBe modelUsage
    read[ModelUsage]("{}") shouldBe ModelUsage()
    read[ModelUsage]("""{"requestCount":4}""") shouldBe ModelUsage().withRequestCount(4L)
  }

  "UsageSummary JSON" should "be unchanged" in {
    write(summary) shouldBe summaryJson
    write(UsageSummary()) shouldBe "{}"
  }

  it should "round-trip, and fill omitted fields from the defaults" in {
    read[UsageSummary](summaryJson) shouldBe summary
    read[UsageSummary]("{}") shouldBe UsageSummary()
    read[UsageSummary]("""{"inputTokens":7,"totalCost":"1.25"}""") shouldBe
      UsageSummary().withInputTokens(7L).withTotalCost(BigDecimal("1.25"))
  }

  it should "keep its BigDecimal codec out of reach of a wildcard import" in {
    assertDoesNotCompile("UsageSummary.bigDecimalRw")
  }

  "ModelPricing JSON" should "be unchanged" in {
    write(pricing) shouldBe """{"inputCostPerToken":0.001,"outputCostPerToken":0.002}"""
    write(ModelPricing()) shouldBe "{}"
  }

  it should "round-trip, and fill omitted fields from the defaults" in {
    read[ModelPricing](write(pricing)) shouldBe pricing
    read[ModelPricing]("{}") shouldBe ModelPricing()
  }
}
