package org.llm4s.llmconnect.provider

import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, completion => completionOf, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.OptionValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.OptionConverters._

/**
 * Streamed token usage (#1132 follow-up to #1209): streaming requests ask for usage with
 * `stream_options.include_usage`, and the usage-only final chunk that produces - no choices,
 * just `usage` - ends up on the streamed `Completion`.
 */
final class OpenAIClientStreamUsageSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hello = Conversation(Seq(UserMessage("hello")))

  private def openAI(model: String): OpenAIConfig =
    OpenAIConfig.fromValues(model, "sk-test", None, "https://example.invalid/v1").value

  private def azure(apiVersion: String, endpoint: String = "https://r.openai.azure.com"): AzureConfig =
    AzureConfig.fromValues("my-deploy", endpoint, "azure-key", apiVersion).value

  private val content = chunk(
    """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}"""
  )
  private val stop = chunk(
    """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )
  private val usageOnly = chunk(
    """{"id":"c1","created":0,"model":"gpt-4o","choices":[],
      |"usage":{"prompt_tokens":12,"completion_tokens":40,"total_tokens":52,
      |"completion_tokens_details":{"reasoning_tokens":24},"prompt_tokens_details":{"cached_tokens":8}}}""".stripMargin
  )

  /** The params `streamComplete` sends for `config`, streaming back `chunks`. */
  private def streamedParams(config: ProviderConfig, model: String): ChatCompletionCreateParams = {
    val seen   = new AtomicReference[ChatCompletionCreateParams]()
    val client = OpenAIClient.forTest(model, transport(streaming = p => { seen.set(p); stream(content, stop) }), config)
    client.streamComplete(hello, CompletionOptions(), _ => ()).value
    seen.get()
  }

  private def includeUsage(params: ChatCompletionCreateParams): Option[Boolean] =
    params.streamOptions().toScala.flatMap(_.includeUsage().toScala).map(_.booleanValue)

  "OpenAIClient.streamComplete" should "ask for usage with stream_options.include_usage" in {
    includeUsage(streamedParams(openAI("gpt-4o"), "gpt-4o")) shouldBe Some(true)
  }

  it should "put the usage from a final chunk with no choices on the Completion" in {
    val client =
      OpenAIClient.forTest("gpt-4o", transport(streaming = _ => stream(content, stop, usageOnly)), openAI("gpt-4o"))
    val chunks = scala.collection.mutable.ListBuffer.empty[String]

    val completion = client.streamComplete(hello, CompletionOptions(), c => chunks += c.content.getOrElse("")).value

    completion.content shouldBe "Hi"
    val usage = completion.usage.value
    usage.promptTokens shouldBe 12
    usage.completionTokens shouldBe 40
    // The service's own total: reasoning tokens are part of completion_tokens, not added to it.
    usage.totalTokens shouldBe 52
    usage.thinkingTokens shouldBe Some(24)
    usage.cachedTokens shouldBe Some(8)
    completion.estimatedCost shouldBe defined
    // The usage-only chunk carries no choice, so it produces no StreamedChunk.
    chunks.toList shouldBe List("Hi", "")
  }

  it should "report no usage when the stream carries none" in {
    val client = OpenAIClient.forTest("gpt-4o", transport(streaming = _ => stream(content, stop)), openAI("gpt-4o"))
    client.streamComplete(hello, CompletionOptions(), _ => ()).value.usage shouldBe None
  }

  it should "not send stream_options on a faked stream, which is an ordinary completion call" in {
    // The registry's transformer fakes streaming for o1; OpenAI rejects stream_options without stream.
    val seen = new AtomicReference[ChatCompletionCreateParams]()
    val response = completionOf(
      """{"id":"c1","created":0,"model":"o1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""
    )
    val client = OpenAIClient.forTest("o1", transport(complete = p => { seen.set(p); response }), openAI("o1"))
    client.streamComplete(hello, CompletionOptions().withReasoning(ReasoningEffort.Low), _ => ()).value
    seen.get().streamOptions().toScala shouldBe None
    seen.get().reasoningEffort().toScala.map(_.asString()) shouldBe Some("low")
  }

  it should "not send stream_options on a non-streaming call" in {
    val seen = new AtomicReference[ChatCompletionCreateParams]()
    val response = completionOf(
      """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""
    )
    val client = OpenAIClient.forTest("gpt-4o", transport(complete = p => { seen.set(p); response }), openAI("gpt-4o"))
    client.complete(hello, CompletionOptions()).value
    seen.get().streamOptions().toScala shouldBe None
  }

  "OpenAIClient.streamComplete on Azure" should "ask for usage on the default api-version" in {
    includeUsage(streamedParams(azure(AzureConfig.DEFAULT_API_VERSION), "my-deploy")) shouldBe Some(true)
  }

  it should "not send stream_options on an api-version older than 2024-09-01-preview, which rejects it" in {
    streamedParams(azure("2024-06-01"), "my-deploy").streamOptions().toScala shouldBe None
  }

  "OpenAIClient.acceptsStreamUsage" should "accept every OpenAI config and Azure from 2024-09-01-preview on" in {
    OpenAIClient.acceptsStreamUsage(openAI("gpt-4o")) shouldBe true
    OpenAIClient.acceptsStreamUsage(azure("V2025_01_01_PREVIEW")) shouldBe true
    OpenAIClient.acceptsStreamUsage(azure("2024-10-21")) shouldBe true
    OpenAIClient.acceptsStreamUsage(azure("2024-09-01-preview")) shouldBe true
    OpenAIClient.acceptsStreamUsage(azure("V2024_08_01_PREVIEW")) shouldBe false
    OpenAIClient.acceptsStreamUsage(azure("2024-02-15-preview")) shouldBe false
    OpenAIClient.acceptsStreamUsage(azure("V2024_06_01")) shouldBe false
    // The unified v1 API's versions are not dates.
    OpenAIClient.acceptsStreamUsage(azure("preview", "https://r.openai.azure.com/openai/v1")) shouldBe true
  }
}
