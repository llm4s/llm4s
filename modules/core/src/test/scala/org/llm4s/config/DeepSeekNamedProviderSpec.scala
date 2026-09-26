package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.provider.DeepSeekProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named `deepseek` provider sections, from validation to a loaded config and a model listing.
 *
 * Core's config-loading specs used DeepSeek as a convenient API-key provider until they moved
 * to the test fixture `FixtureChatProvider` (#1132). The DeepSeek facts they checked in
 * passing - its default base URL, its API-key hint and its `/models` endpoint - are kept
 * here, so they move with DeepSeek when it leaves core, as `AnthropicNamedProviderSpec` and
 * `OpenAINamedProviderSpec` did with theirs.
 */
class DeepSeekNamedProviderSpec extends AnyWordSpec with Matchers:

  private val hocon =
    """
      |llm4s {
      |  providers {
      |    provider = "deepseek-main"
      |    deepseek-main {
      |      provider = "deepseek"
      |      model = "deepseek-chat"
      |      apiKey = "deepseek-key"
      |    }
      |  }
      |}
      |""".stripMargin

  "Llm4sConfig" should {

    "load a DeepSeek named provider end to end, defaulting the base URL" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "deepseek-main") match
        case Right(deepseek: DeepSeekConfig) =>
          deepseek.model shouldBe "deepseek-chat"
          deepseek.apiKey shouldBe "deepseek-key"
          deepseek.baseUrl shouldBe DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL
        case other =>
          fail(s"Expected DeepSeekConfig, got $other")
    }

    "list a DeepSeek provider's models from its default base URL" in {
      val responseBody =
        """{ "data": [ { "id": "deepseek-chat", "created": 1710000000, "owned_by": "deepseek" } ] }"""
      val httpClient = new MockHttpClient(HttpResponse(200, responseBody, Map.empty))

      Llm4sConfig.listModels("deepseek-main", ConfigSource.string(hocon), httpClient) match
        case Right(models) =>
          models.map(_.name.asString) shouldBe List("deepseek-chat")
          models.map(_.provider) shouldBe List(ProviderId("deepseek"))
          httpClient.lastUrl shouldBe Some(s"${DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL}/models")
        case Left(err) =>
          fail(s"Expected listed models, got error: ${err.message}")
    }
  }

  "DeepSeek section validation" should {

    "name DeepSeek's API-key env var, and not demand a baseUrl" in {
      val name = ProviderName("my-deepseek")
      val section =
        RawNamedProviderSection(Some("deepseek"), Some("deepseek-chat"), None, None, None, None, None)

      val message = NamedProviderConfigNormalizer
        .normalize(name, section)
        .flatMap(NamedProviderSectionValidator.validate(name, DeepSeekProvider, _))
        .left
        .toOption
        .getOrElse(fail("Expected a missing-apiKey failure"))
        .message

      message should include("Provider 'my-deepseek' (provider = deepseek) is missing required fields")
      message should include("e.g. apiKey = ${?DEEPSEEK_API_KEY}")
      (message should not).include("baseUrl")
      DeepSeekProvider.configSpec.defaultBaseUrl shouldBe Some(DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)
    }
  }
