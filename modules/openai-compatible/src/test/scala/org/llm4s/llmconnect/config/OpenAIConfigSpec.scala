package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `OpenAIConfig`'s growth-prone construction (#1388): the short `apply` Java and Kotlin call, and
 * a `with*` setter per field in place of the private `copy`.
 */
class OpenAIConfigSpec extends AnyFunSuite with Matchers {

  private val openai = OpenAIConfig("k", "gpt-4o", None, OpenAIConfig.DEFAULT_BASE_URL, 128000, 4096)

  test("OpenAIConfig(apiKey, model) defaults the base URL and takes the context window from the model name") {
    OpenAIConfig("k", "gpt-4o") shouldBe openai
    OpenAIConfig("k", "gpt-4").contextWindow shouldBe 8192
    OpenAIConfig("k", "gpt-4o").providerId shouldBe ProviderId("openai")
  }

  test("OpenAIConfig's setters each change one field") {
    val adjusted = openai
      .withApiKey("k2")
      .withOrganization("org-1")
      .withBaseUrl("https://proxy.example.com/v1")
      .withContextWindow(64000)
      .withReserveCompletion(2048)
      .withExplicitProviderId(ProviderId("requesty"))

    adjusted shouldBe OpenAIConfig(
      "k2",
      "gpt-4o",
      Some("org-1"),
      "https://proxy.example.com/v1",
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
}
