package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `EmbeddingResponse`, `EmbeddingUsage`, `UsageSummary` and `ModelUsage` are growth-prone (#2356): built through
 * `apply`, changed through `with*`.
 */
class GrowthSafeResponseTypesSpec extends AnyFlatSpec with Matchers {

  "EmbeddingResponse" should "default everything but the embeddings" in {
    val response = EmbeddingResponse(Seq(Seq(1.0)))

    response.metadata shouldBe empty
    response.modality shouldBe None
    response.dim shouldBe None
    response.usage shouldBe None
    EmbeddingResponse.empty.embeddings shouldBe empty
  }

  it should "change each field through its setter" in {
    val usage = EmbeddingUsage(3, 3)
    val response = EmbeddingResponse.empty
      .withEmbeddings(Seq(Seq(0.5, 0.25)))
      .withMetadata(Map("provider" -> "fixture"))
      .withModality(Text)
      .withDim(2)
      .withUsage(usage)

    response shouldBe EmbeddingResponse(
      Seq(Seq(0.5, 0.25)),
      Map("provider" -> "fixture"),
      Some(Text),
      Some(2),
      Some(usage)
    )
  }

  it should "clear an Option field with None" in {
    val full =
      EmbeddingResponse(Seq(Seq(1.0)), modality = Some(Text), dim = Some(1), usage = Some(EmbeddingUsage(1, 1)))

    full.withModality(None).withDim(None).withUsage(None) shouldBe EmbeddingResponse(Seq(Seq(1.0)))
    full.withModality(Some(Audio)).withDim(Some(4)).withUsage(Some(EmbeddingUsage(2, 2))) shouldBe
      EmbeddingResponse(Seq(Seq(1.0)), modality = Some(Audio), dim = Some(4), usage = Some(EmbeddingUsage(2, 2)))
  }

  "EmbeddingUsage" should "change each field through its setter" in {
    val usage = EmbeddingUsage(1, 1).withPromptTokens(10).withTotalTokens(12)

    usage shouldBe EmbeddingUsage(10, 12)
    usage match {
      case EmbeddingUsage(prompt, total) => (prompt, total) shouldBe ((10, 12))
    }
  }

  "ModelUsage" should "change each field through its setter" in {
    val usage = ModelUsage()
      .withRequestCount(2L)
      .withInputTokens(100L)
      .withOutputTokens(50L)
      .withThinkingTokens(10L)
      .withTotalCost(BigDecimal("0.25"))

    usage shouldBe ModelUsage(2L, 100L, 50L, 10L, BigDecimal("0.25"))
  }

  "UsageSummary" should "change each field through its setter" in {
    val byModel = Map("gpt-4o" -> ModelUsage(requestCount = 1L))
    val summary = UsageSummary()
      .withRequestCount(1L)
      .withInputTokens(20L)
      .withOutputTokens(5L)
      .withThinkingTokens(2L)
      .withTotalCost(BigDecimal("0.01"))
      .withByModel(byModel)

    summary shouldBe UsageSummary(1L, 20L, 5L, 2L, BigDecimal("0.01"), byModel)
  }

  it should "default every field to zero or empty" in {
    UsageSummary() shouldBe UsageSummary(0L, 0L, 0L, 0L, BigDecimal(0), Map.empty)
  }
}
