package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `OpenAIConfig`'s growth-prone construction (#1388): the short `apply` Java and Kotlin call, and
 * a `with*` setter per field in place of the private `copy`.
 */
class OpenAIConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private val openai = OpenAIConfig(Secret("k"), ModelName("gpt-4o"), None, OpenAIConfig.DEFAULT_BASE_URL, 128000, 4096)

  test("OpenAIConfig(apiKey, model) defaults the base URL and takes the context window from the model name") {
    OpenAIConfig(Secret("k"), ModelName("gpt-4o")) shouldBe openai
    OpenAIConfig(Secret("k"), ModelName("gpt-4")).contextWindow shouldBe 8192
    OpenAIConfig(Secret("k"), ModelName("gpt-4o")).providerId shouldBe ProviderId("openai")
  }

  test("OpenAIConfig's setters each change one field") {
    val adjusted = openai
      .withApiKey(Secret("k2"))
      .withOrganization("org-1")
      .withBaseUrl(BaseUrl("https://proxy.example.com/v1"))
      .withContextWindow(64000)
      .withReserveCompletion(2048)
      .withExplicitProviderId(ProviderId("requesty"))

    adjusted shouldBe OpenAIConfig(
      Secret("k2"),
      ModelName("gpt-4o"),
      Some("org-1"),
      BaseUrl("https://proxy.example.com/v1"),
      64000,
      2048,
      Some(ProviderId("requesty"))
    )
    adjusted.providerId shouldBe ProviderId("requesty")
  }

  test("OpenAIConfig's Option setters also take an Option, so a field can be cleared") {
    val set = openai.withOrganization("org-1").withExplicitProviderId(ProviderId("requesty"))

    set.withOrganization(None).organization shouldBe None
    set.withExplicitProviderId(None).providerId shouldBe ProviderId("openai")
  }

  test("OpenAIConfig has no api and no reasoning summary unless set") {
    val config = OpenAIConfig(Secret("sk-test"), ModelName("gpt-4o"))
    config.api shouldBe None
    config.reasoningSummary shouldBe None
  }

  test("OpenAIConfig carries the api and the reasoning summary it is given, and clears them") {
    val config = OpenAIConfig(Secret("sk-test"), ModelName("gpt-4o"))
      .withApi(OpenAIApi.Responses)
      .withReasoningSummary(OpenAIReasoningSummary.Auto)
    config.api shouldBe Some(OpenAIApi.Responses)
    config.reasoningSummary shouldBe Some(OpenAIReasoningSummary.Auto)
    config.withApi(None).api shouldBe None
    config.withReasoningSummary(None).reasoningSummary shouldBe None
    config.toString should include("api=Some(Responses)")
  }

  test("OpenAIApi.parse reads the two config values and refuses anything else, naming both") {
    OpenAIApi.parse("responses") shouldBe Right(OpenAIApi.Responses)
    OpenAIApi.parse("chat-completions") shouldBe Right(OpenAIApi.ChatCompletions)
    val error = OpenAIApi.parse("Responses API").left.value
    error.message should include("responses")
    error.message should include("chat-completions")
  }

  test("OpenAIReasoningSummary.parse reads auto, concise and detailed and refuses anything else") {
    OpenAIReasoningSummary.parse("auto") shouldBe Right(OpenAIReasoningSummary.Auto)
    OpenAIReasoningSummary.parse("concise") shouldBe Right(OpenAIReasoningSummary.Concise)
    OpenAIReasoningSummary.parse("detailed") shouldBe Right(OpenAIReasoningSummary.Detailed)
    OpenAIReasoningSummary.parse("full").isLeft shouldBe true
  }
}
