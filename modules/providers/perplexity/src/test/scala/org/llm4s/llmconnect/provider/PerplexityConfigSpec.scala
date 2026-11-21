package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, PerplexityConfig, ProviderTimeouts }
import org.llm4s.security.Secret
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

class PerplexityConfigSpec extends AnyWordSpec with Matchers:

  import PerplexityFixtures.{ *, given }

  private def build(
    model: String = "perplexity/sonar",
    key: String = "k",
    baseUrl: String = PerplexityConfig.DEFAULT_BASE_URL,
    contextWindow: Int = 1000,
    reserve: Int = 10
  ) =
    PerplexityConfig.fromValues(model, Secret(key), baseUrl, contextWindow = contextWindow, reserveCompletion = reserve)

  "PerplexityConfig.fromValues" should {

    "trim the key and model and drop trailing slashes from the base URL" in {
      val config =
        build(model = " perplexity/sonar ", key = " k ", baseUrl = "https://api.perplexity.ai//").toOption.get
      config.apiKey.reveal shouldBe "k"
      config.model shouldBe "perplexity/sonar"
      config.baseUrl shouldBe "https://api.perplexity.ai"
      config.endpointUrl shouldBe Some("https://api.perplexity.ai")
      config.providerId.asString shouldBe "perplexity"
    }

    "refuse a blank key or model, a non-https base URL and a bad window or reserve" in {
      def field(result: Either[org.llm4s.error.LLMError, ?]): List[String] = result match
        case Left(ConfigurationError(_, fields)) => fields
        case other                               => fail(s"expected a ConfigurationError, got $other")
      field(build(key = " ")) shouldBe List("apiKey")
      field(build(model = "")) shouldBe List("model")
      field(build(baseUrl = " / ")) shouldBe List("baseUrl")
      field(build(baseUrl = "http://api.perplexity.ai")) shouldBe List("baseUrl")
      field(build(baseUrl = "ftp://x")) shouldBe List("baseUrl")
      field(build(baseUrl = "https://")) shouldBe List("baseUrl")
      field(build(contextWindow = 0)) shouldBe List("contextWindow")
      field(build(reserve = -1)) shouldBe List("reserveCompletion")
      field(build(reserve = 1000)) shouldBe List("reserveCompletion")
    }

    "allow plain http to a loopback host" in {
      build(baseUrl = "http://localhost:8080").isRight shouldBe true
      build(baseUrl = "http://127.0.0.1:8080").isRight shouldBe true
    }

    "carry overrides through the with-setters and keep the key out of toString" in {
      val config = build().toOption.get
        .withModel("openai/gpt-5.6-sol")
        .withTimeouts(ProviderTimeouts.default.withRequest(3.seconds))
        .withNetwork(org.llm4s.http.HttpNetworkSettings.default)
        .withWebSearch(false)
        .withHeaders(Map("X-A" -> "secret-header-value"))
        .withApiKeyProvider(None)
      config.model shouldBe "openai/gpt-5.6-sol"
      config.timeouts.requestOr(1.second) shouldBe 3.seconds
      config.webSearch shouldBe false
      config.apiKeyProvider shouldBe None
      (config.toString should not).include("secret-header-value")
      config.toString should include("X-A")
      (build(key = "pplx-very-secret").toOption.get.toString should not).include("pplx-very-secret")
    }
  }

  "PerplexityProvider.registryWindow" should {

    "look up the model's own vendor strictly, and give none for an unknown or unprefixed name" in {
      given ContextWindowResolver = ContextWindowResolver(summon[org.llm4s.model.ModelRegistryService])
      PerplexityProvider.registryWindow("perplexity/sonar-pro") shouldBe Some(200000)
      PerplexityProvider.registryWindow("perplexity/no-such-model-xyz") shouldBe None
      PerplexityProvider.registryWindow("sonar-pro") shouldBe None
      PerplexityProvider.registryWindow("/sonar") shouldBe None
    }
  }

  "the client's timeouts" should {

    "default to five minutes and ten for a stream, and follow the section's" in {
      val http = new StubHttp()
      client(http).requestTimeout shouldBe 5.minutes
      client(http).streamTimeout shouldBe 10.minutes
      val tuned = config().withTimeouts(ProviderTimeouts.default.withRequest(7.seconds).withStream(9.seconds))
      client(http, tuned).requestTimeout shouldBe 7.seconds
      client(http, tuned).streamTimeout shouldBe 9.seconds
    }
  }
