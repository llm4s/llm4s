package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.TokenUsage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GeminiUsageSpec extends AnyFlatSpec with Matchers {

  "GeminiUsage.fromResponse" should "read every usageMetadata count" in {
    val json = ujson.read(
      """{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"thoughtsTokenCount":40,
        |"cachedContentTokenCount":64,"totalTokenCount":150}}""".stripMargin
    )
    GeminiUsage.fromResponse(json) shouldBe Some(
      TokenUsage(
        promptTokens = 100,
        completionTokens = 5,
        totalTokens = 150,
        thinkingTokens = Some(40),
        cachedTokens = Some(64)
      )
    )
  }

  it should "leave thinking and cached tokens empty when the response reports none" in {
    val json = ujson.read("""{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""")
    GeminiUsage.fromResponse(json) shouldBe Some(TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15))
  }

  it should "count a missing candidatesTokenCount as zero and sum a missing total" in {
    val json = ujson.read("""{"usageMetadata":{"promptTokenCount":10,"thoughtsTokenCount":7}}""")
    GeminiUsage.fromResponse(json) shouldBe Some(
      TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 17, thinkingTokens = Some(7))
    )
  }

  it should "read no usage without usageMetadata or a promptTokenCount" in {
    GeminiUsage.fromResponse(ujson.read("""{"candidates":[]}""")) shouldBe None
    GeminiUsage.fromResponse(ujson.read("""{"usageMetadata":{"candidatesTokenCount":5}}""")) shouldBe None
  }

  it should "ignore a count that is negative or not a number" in {
    val json = ujson.read(
      """{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":"five","cachedContentTokenCount":-1}}"""
    )
    GeminiUsage.fromResponse(json) shouldBe Some(TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10))
  }
}
