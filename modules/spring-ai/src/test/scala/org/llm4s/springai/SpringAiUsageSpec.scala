package org.llm4s.springai

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.provider.CostEstimator
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.ai.chat.metadata.{ DefaultUsage, EmptyUsage }

import java.util.Optional

/**
 * Spring AI's usage in llm4s's terms (#2765): `promptTokens` is the whole input, cache reads and writes included,
 * whichever Spring AI model reported it. The usages are built as Spring AI 2.0's own models build them: Anthropic's
 * `AnthropicChatModel.getDefaultUsage` (prompt = Anthropic's `input_tokens`, total = input + output, the SDK's usage
 * as native), Bedrock's `BedrockProxyChatModel` (prompt = Converse's `inputTokens`, total = Converse's `totalTokens`),
 * OpenAI's `OpenAiChatModel.getDefaultUsage` (prompt = `prompt_tokens`, reads = `cached_tokens`, no writes).
 */
class SpringAiUsageSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

  // input neither read nor written, cache reads, cache writes, output
  private val (uncached, reads, writes, output) = (100, 4000, 1000, 50)

  private def anthropicNative: com.anthropic.models.messages.Usage =
    com.anthropic.models.messages.Usage
      .builder()
      .inputTokens(uncached.toLong)
      .outputTokens(output.toLong)
      .cacheReadInputTokens(reads.toLong)
      .cacheCreationInputTokens(writes.toLong)
      .cacheCreation(Optional.empty())
      .inferenceGeo(Optional.empty[String]())
      .outputTokensDetails(Optional.empty())
      .serverToolUse(Optional.empty())
      .serviceTier(Optional.empty())
      .build()

  /** What `AnthropicChatModel.getDefaultUsage` returns for one cached request. */
  private def anthropicUsage(native: AnyRef = anthropicNative) =
    new DefaultUsage(uncached, output, uncached + output, native, reads.toLong, writes.toLong)

  /** The usage llm4s's own Anthropic client reports for the same request. */
  private val llm4sAnthropic = TokenUsage(
    promptTokens = uncached + reads + writes,
    completionTokens = output,
    totalTokens = uncached + reads + writes + output,
    cachedTokens = Some(reads),
    cacheCreationTokens = Some(writes)
  )

  "SpringAiMapping.tokenUsage" should "add Anthropic's cache reads and writes to its prompt and total" in {
    SpringAiMapping.tokenUsage(anthropicUsage()) shouldBe Some(llm4sAnthropic)
  }

  it should "price a cached Anthropic request through the bridge as llm4s's own client's usage is priced" in {
    val model   = "claude-sonnet-4-5"
    val bridged = SpringAiMapping.tokenUsage(anthropicUsage()).getOrElse(fail("no usage"))
    val pricing =
      summon[ModelRegistryService].lookup(ModelName(model)).toOption.map(_.pricing).getOrElse(fail("no model"))
    val expected = for {
      in    <- pricing.inputCostPerToken
      read  <- pricing.cacheReadInputTokenCost
      write <- pricing.cacheCreationInputTokenCost
      out   <- pricing.outputCostPerToken
    } yield uncached * in + reads * read + writes * write + output * out
    expected shouldBe defined
    CostEstimator.estimate(ModelName(model), bridged).get.toDouble shouldBe expected.get +- 1e-12
    CostEstimator.estimate(ModelName(model), bridged) shouldBe CostEstimator.estimate(ModelName(model), llm4sAnthropic)
    // passed through as Spring AI reports it, the request cost no more than its uncached input and output
    val passedThrough = TokenUsage(uncached, output, uncached + output, cachedTokens = Some(reads))
    CostEstimator.estimate(ModelName(model), passedThrough).get.toDouble should be < expected.get
  }

  it should "add Bedrock Converse's cache counts to its prompt, keeping its total, which already has them" in {
    val native = software.amazon.awssdk.services.bedrockruntime.model.TokenUsage
      .builder()
      .inputTokens(uncached)
      .outputTokens(output)
      .totalTokens(uncached + reads + writes + output)
      .cacheReadInputTokens(reads)
      .cacheWriteInputTokens(writes)
      .build()
    val usage =
      new DefaultUsage(uncached, output, uncached + reads + writes + output, native, reads.toLong, writes.toLong)
    SpringAiMapping.tokenUsage(usage) shouldBe Some(llm4sAnthropic)
  }

  it should "add them after a tool-calling loop, whose summed usage has no native usage but reports cache writes" in {
    val summed = new DefaultUsage(uncached, output, uncached + output, null, reads.toLong, writes.toLong)
    SpringAiMapping.tokenUsage(summed) shouldBe Some(llm4sAnthropic)
  }

  it should "pass OpenAI's prompt through: its prompt_tokens already include the cached tokens" in {
    val openAi = new DefaultUsage(1200, 30, 1230, "openai-native", 1000L, null)
    SpringAiMapping.tokenUsage(openAi) shouldBe Some(TokenUsage(1200, 30, 1230, cachedTokens = Some(1000)))
    // nor when the summed usage of a loop dropped the native one: OpenAI reports no cache writes
    SpringAiMapping.tokenUsage(new DefaultUsage(1200, 30, 1230, null, 1000L, null)) shouldBe
      Some(TokenUsage(1200, 30, 1230, cachedTokens = Some(1000)))
  }

  it should "add the reads to a prompt they exceed, which a count that includes them cannot be" in {
    val unknown = new DefaultUsage(10, 5, 15, "custom-native", 400L, null)
    SpringAiMapping.tokenUsage(unknown) shouldBe Some(TokenUsage(410, 5, 415, cachedTokens = Some(400)))
  }

  it should "leave a usage without cache counts as it is, and report none for an empty usage" in {
    SpringAiMapping.tokenUsage(anthropicUsage(native = null).copyWithoutCache) shouldBe
      Some(TokenUsage(uncached, output, uncached + output))
    SpringAiMapping.tokenUsage(new EmptyUsage()) shouldBe None
  }

  extension (u: DefaultUsage)
    private def copyWithoutCache: DefaultUsage =
      new DefaultUsage(u.getPromptTokens, u.getCompletionTokens, u.getTotalTokens)
}
