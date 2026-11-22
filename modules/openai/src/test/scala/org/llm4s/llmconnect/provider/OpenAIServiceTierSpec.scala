package org.llm4s.llmconnect.provider

import org.llm4s.security.Secret
import com.openai.core.ObjectMappers
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ completion => completionOf, transport }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference

/** `CompletionOptions.serviceTier` as `service_tier` on OpenAI and Azure OpenAI, and not sent elsewhere (#2726). */
final class OpenAIServiceTierSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hello = Conversation(Seq(UserMessage("hello")))

  private val okResponse =
    """{"id":"c1","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],
      |"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".stripMargin

  private val openAI: OpenAIConfig =
    OpenAIConfig.fromValues("gpt-4o", Secret("sk-test"), "https://example.invalid/v1").value

  private val azure: AzureConfig =
    AzureConfig
      .fromValues("gpt-4o", Secret("azure-key"), "https://r.openai.azure.com", AzureConfig.DEFAULT_API_VERSION)
      .value

  /** The request body and completion of `complete` for `provider`. */
  private def send(config: ProviderConfig, provider: String, tier: ServiceTier): (ujson.Value, Completion) = {
    val seen = new AtomicReference[ChatCompletionCreateParams]()
    val client = OpenAIClient.forTest(
      "gpt-4o",
      transport(complete = p => { seen.set(p); completionOf(okResponse) }),
      config,
      provider = ProviderId(provider)
    )
    val completion = client.complete(hello, CompletionOptions().withServiceTier(tier)).value
    (ujson.read(ObjectMappers.jsonMapper().writeValueAsString(seen.get()._body())), completion)
  }

  "OpenAIClient" should "send every tier to OpenAI as service_tier, reporting nothing" in {
    Seq(
      ServiceTier.Auto     -> "auto",
      ServiceTier.Default  -> "default",
      ServiceTier.Flex     -> "flex",
      ServiceTier.Priority -> "priority"
    ).foreach { (tier, wire) =>
      val (body, completion) = send(openAI, "openai", tier)
      body("service_tier").str shouldBe wire
      completion.adjustments shouldBe empty
    }
  }

  it should "send Azure OpenAI every tier but flex, which it reports dropped" in {
    send(azure, "azure", ServiceTier.Priority)._1("service_tier").str shouldBe "priority"
    val (body, completion) = send(azure, "azure", ServiceTier.Flex)
    body.obj.contains("service_tier") shouldBe false
    completion.adjustments shouldBe List(
      RequestAdjustment.Dropped(RequestOption.ServiceTier, "Azure OpenAI has no flex tier")
    )
  }

  it should "send no tier to Requesty, and report it dropped" in {
    val (body, completion) = send(openAI, "requesty", ServiceTier.Flex)
    body.obj.contains("service_tier") shouldBe false
    completion.adjustments shouldBe List(
      RequestAdjustment.Dropped(RequestOption.ServiceTier, "Requesty has no service tier to choose; Flex is not sent")
    )
  }

  it should "send no service_tier without one" in {
    val seen = new AtomicReference[ChatCompletionCreateParams]()
    val client =
      OpenAIClient.forTest("gpt-4o", transport(complete = p => { seen.set(p); completionOf(okResponse) }), openAI)
    client.complete(hello, CompletionOptions()).value.adjustments shouldBe empty
    ujson.read(ObjectMappers.jsonMapper().writeValueAsString(seen.get()._body())).obj.contains("service_tier") shouldBe
      false
  }

  it should "send ServiceTier.Other as written" in {
    send(openAI, "openai", ServiceTier.Other("scale"))._1("service_tier").str shouldBe "scale"
  }

  "servedTier" should "be the tier the reply's service_tier names, another kept as Other (#2746)" in {
    def served(tier: String): Option[ServiceTier] = {
      val response =
        s"""{"id":"c1","created":0,"model":"m","service_tier":"$tier",""" +
          """"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}"""
      OpenAIClient
        .forTest("gpt-4o", transport(complete = _ => completionOf(response)), openAI)
        .complete(hello, CompletionOptions())
        .value
        .servedTier
    }
    served("default") shouldBe Some(ServiceTier.Default)
    served("flex") shouldBe Some(ServiceTier.Flex)
    served("priority") shouldBe Some(ServiceTier.Priority)
    served("auto") shouldBe Some(ServiceTier.Auto)
    served("scale") shouldBe Some(ServiceTier.Other("scale"))
    served("") shouldBe None
    OpenAIClient
      .forTest("gpt-4o", transport(complete = _ => completionOf(okResponse)), openAI)
      .complete(hello, CompletionOptions())
      .value
      .servedTier shouldBe None
  }

  it should "be the tier a streamed chunk names" in {
    val chunks = Seq(
      """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","service_tier":"flex",""" +
        """"choices":[{"index":0,"delta":{"content":"ok"}}]}""",
      """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","service_tier":"flex",""" +
        """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
    ).map(OpenAISdkFixtures.chunk)
    val completion = OpenAIClient
      .forTest("gpt-4o", transport(streaming = _ => OpenAISdkFixtures.stream(chunks*)), openAI)
      .streamComplete(hello, CompletionOptions(), _ => ())
      .value
    (completion.content, completion.servedTier) shouldBe ("ok", Some(ServiceTier.Flex))
  }
}
