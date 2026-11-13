package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.provider.CostEstimator
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, UnmappedOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RFC 0008 PR 6 in core: `PromptCache`, the option, the reports, and the price of a 1-hour cache write. */
class PromptCacheSpec extends AnyFlatSpec with Matchers {

  "PromptCache.default" should "cache the system prompt and the tools for the short lifetime" in {
    PromptCache.default.scope shouldBe Set(PromptCache.Scope.System, PromptCache.Scope.Tools)
    PromptCache.default.ttl shouldBe PromptCache.Ttl.Short
  }

  "PromptCache" should "be built with apply and changed with its setters" in {
    val all = PromptCache(PromptCache.Scope.values.toSet, PromptCache.Ttl.Long)
    all.scope shouldBe Set(PromptCache.Scope.System, PromptCache.Scope.Tools, PromptCache.Scope.Conversation)
    all.ttl shouldBe PromptCache.Ttl.Long
    PromptCache.default.withTtl(PromptCache.Ttl.Long) shouldBe
      PromptCache(Set(PromptCache.Scope.System, PromptCache.Scope.Tools), PromptCache.Ttl.Long)
    PromptCache.default.withScope(Set(PromptCache.Scope.Conversation)).scope shouldBe Set(
      PromptCache.Scope.Conversation
    )
  }

  "CompletionOptions.promptCache" should "be unset by default, and set and cleared by its setters" in {
    CompletionOptions().promptCache shouldBe None
    CompletionOptions().withPromptCache(PromptCache.default).promptCache shouldBe Some(PromptCache.default)
    CompletionOptions(promptCache = Some(PromptCache.default)).withPromptCache(None) shouldBe CompletionOptions()
  }

  "UnmappedOptions" should "report a prompt cache as dropped, or as not needed by a provider that caches on its own" in {
    val asked = CompletionOptions().withPromptCache(PromptCache.default)
    UnmappedOptions.promptCache("m", asked).map(_.option) shouldBe List(RequestOption.PromptCache)
    UnmappedOptions.promptCache("m", asked).head shouldBe a[RequestAdjustment.Dropped]
    UnmappedOptions.promptCacheAutomatic("OpenAI", asked) shouldBe List(
      RequestAdjustment.NotNeeded(RequestOption.PromptCache, "OpenAI caches a repeated prompt prefix on its own")
    )
    UnmappedOptions.promptCache("m", CompletionOptions()) shouldBe empty
    UnmappedOptions.promptCacheAutomatic("OpenAI", CompletionOptions()) shouldBe empty
  }

  // -------- the price of a 1-hour write --------

  private def metadata(pricing: ModelPricing) =
    ModelMetadata(
      modelId = "m",
      provider = "anthropic",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = None,
      inputCostPerToken = pricing.inputCostPerToken,
      outputCostPerToken = pricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

  private def pricing = ModelPricing(
    inputCostPerToken = Some(1.0),
    outputCostPerToken = Some(0.0),
    cacheReadInputTokenCost = Some(0.1),
    cacheCreationInputTokenCost = Some(1.25),
    cacheCreationInputTokenCostAbove1hr = Some(2.0)
  )

  "CostEstimator" should "price 1-hour cache writes at their own rate and the rest at the short rate" in {
    val usage = TokenUsage(
      promptTokens = 1000,
      completionTokens = 0,
      totalTokens = 1000,
      cachedTokens = Some(0),
      cacheCreationTokens = Some(1000),
      longCacheCreationTokens = Some(400)
    )
    // 600 short writes at 1.25 and 400 long writes at 2.0
    CostEstimator.estimateFromMetadata(Some(metadata(pricing)), usage) shouldBe Some(600 * 1.25 + 400 * 2.0)
  }

  it should "price every write at the short rate when the usage does not break out the long ones" in {
    val usage = TokenUsage(1000, 0, 1000, cachedTokens = Some(0), cacheCreationTokens = Some(1000))
    CostEstimator.estimateFromMetadata(Some(metadata(pricing)), usage) shouldBe Some(1000 * 1.25)
  }

  it should "price long writes at the short rate when the model has no 1-hour price" in {
    val usage =
      TokenUsage(
        1000,
        0,
        1000,
        cachedTokens = Some(0),
        cacheCreationTokens = Some(1000),
        longCacheCreationTokens = Some(400)
      )
    CostEstimator.estimateFromMetadata(
      Some(metadata(pricing.withCacheCreationInputTokenCostAbove1hr(None))),
      usage
    ) shouldBe Some(1000 * 1.25)
  }

  it should "never count more long writes than writes" in {
    val usage =
      TokenUsage(
        100,
        0,
        100,
        cachedTokens = Some(0),
        cacheCreationTokens = Some(100),
        longCacheCreationTokens = Some(500)
      )
    CostEstimator.estimateFromMetadata(Some(metadata(pricing)), usage) shouldBe Some(100 * 2.0)
  }

  "ModelPricing.fromJson" should "read the 1-hour cache-write price" in {
    ModelPricing
      .fromJson(ujson.Obj("cache_creation_input_token_cost_above_1hr" -> 6e-6))
      .cacheCreationInputTokenCostAbove1hr shouldBe Some(6e-6)
    ModelPricing.fromJson(ujson.Obj()).cacheCreationInputTokenCostAbove1hr shouldBe None
  }

  "The bundled metadata" should "carry the 1-hour price for a current Claude model" in {
    val registry = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    registry.lookup("claude-opus-4-7").toOption.get.pricing.cacheCreationInputTokenCostAbove1hr.isDefined shouldBe true
  }

  "TokenUsage.longCacheCreationTokens" should "be unset by default and set by its setters" in {
    val usage = TokenUsage(1, 1, 2)
    usage.longCacheCreationTokens shouldBe None
    usage.withLongCacheCreationTokens(5).longCacheCreationTokens shouldBe Some(5)
    usage.withLongCacheCreationTokens(5).withLongCacheCreationTokens(None) shouldBe usage
  }

  "StreamingAccumulator" should "carry the 1-hour cache writes onto the streamed completion's usage" in {
    val accumulator = StreamingAccumulator.create()
    accumulator.updateUsage(
      TokenUsage(100, 0, 0, cacheCreationTokens = Some(80), longCacheCreationTokens = Some(80))
    )
    accumulator.updateUsage(TokenUsage(0, 20, 0))
    val usage = accumulator.toCompletion.toOption.get.usage.get
    usage.cacheCreationTokens shouldBe Some(80)
    usage.longCacheCreationTokens shouldBe Some(80)
  }
}
