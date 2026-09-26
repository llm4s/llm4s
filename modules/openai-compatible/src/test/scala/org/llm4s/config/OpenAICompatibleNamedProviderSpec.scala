package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.provider.OpenAICompatibleProvider
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named provider sections for the providers `llm4s-openai-compatible` holds.
 *
 * The DeepSeek, Z.ai and OpenRouter cases are core's `NamedProviderConfigValidatorSpec`
 * cases, which moved here with the providers (#1132). The rest is the generic
 * `openai-compatible` provider, from HOCON to a working config: `baseUrl` and `model`
 * required, `apiKey` optional, and the context window, reserve and headers read from the
 * section.
 */
class OpenAICompatibleNamedProviderSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

    "validate and normalize a DeepSeek named provider section" in {
      validate(
        "deepseek-main",
        RawNamedProviderSection(
          provider = Some("deepseek"),
          model = Some("deepseek-chat"),
          baseUrl = Some("https://api.deepseek.com"),
          apiKey = Some("deepseek-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("deepseek")
          cfg.model.asString shouldBe "deepseek-chat"
          cfg.apiKey.map(_.asKey) shouldBe Some("deepseek-key")
        case Left(err) =>
          fail(s"Expected DeepSeek NamedProviderConfig, got error: ${err.message}")
    }

    "accept an openai-compatible section with no apiKey" in {
      validate(
        "local-vllm",
        RawNamedProviderSection(
          provider = Some("openai-compatible"),
          model = Some("qwen"),
          baseUrl = Some("http://localhost:8000/v1"),
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("openai-compatible")
          cfg.apiKey shouldBe None
        case Left(err) =>
          fail(s"Expected a valid openai-compatible section, got error: ${err.message}")
    }

    "demand a baseUrl for an openai-compatible section, naming a usable env var" in {
      val message = validate(
        "no-url",
        RawNamedProviderSection(
          provider = Some("openai-compatible"),
          model = Some("qwen"),
          baseUrl = None,
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None
        )
      ).left.toOption.getOrElse(fail("Expected a missing-baseUrl failure")).message

      message should include("Provider 'no-url' (provider = openai-compatible) is missing required fields")
      message should include("baseUrl: set OPENAI_COMPATIBLE_BASE_URL (e.g. http://localhost:8000/v1)")
      (message should not).include("apiKey")
    }
  }

  private val registryService        = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService = registryService

  private val hocon =
    """
      |llm4s {
      |  providers {
      |    provider = "local-vllm"
      |    local-vllm {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1/"
      |      model = "Qwen/Qwen2.5-7B-Instruct"
      |    }
      |    groq-main {
      |      provider = "openai-compatible"
      |      baseUrl = "https://api.groq.com/openai/v1"
      |      model = "llama-3.3-70b-versatile"
      |      apiKey = "gsk-test"
      |      contextWindow = 131072
      |      reserveCompletion = 8192
      |      headers {
      |        X-Team = "search"
      |        X-Gateway-Token = "secret-token"
      |      }
      |    }
      |    bad-window {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      contextWindow = 1000
      |      reserveCompletion = 1000
      |    }
      |  }
      |}
      |""".stripMargin

  "Llm4sConfig" should {

    "load an openai-compatible section with no apiKey, with conservative defaults" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "local-vllm") match
        case Right(cfg: OpenAICompatibleConfig) =>
          cfg.model shouldBe "Qwen/Qwen2.5-7B-Instruct"
          cfg.baseUrl shouldBe "http://localhost:8000/v1"
          cfg.apiKey shouldBe None
          cfg.contextWindow shouldBe OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW
          cfg.reserveCompletion shouldBe OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION
          cfg.headers shouldBe empty
        case other =>
          fail(s"Expected OpenAICompatibleConfig, got $other")
    }

    "load the key, context window, reserve and headers a section sets" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "groq-main") match
        case Right(cfg: OpenAICompatibleConfig) =>
          cfg.apiKey shouldBe Some("gsk-test")
          cfg.contextWindow shouldBe 131072
          cfg.reserveCompletion shouldBe 8192
          cfg.headers shouldBe Map("X-Team" -> "search", "X-Gateway-Token" -> "secret-token")
          (cfg.toString should not).include("gsk-test")
          (cfg.toString should not).include("secret-token")
        case other =>
          fail(s"Expected OpenAICompatibleConfig, got $other")
    }

    "keep several openai-compatible instances side by side" in {
      val loaded =
        Seq("local-vllm", "groq-main").map(name => Llm4sConfig.provider(ConfigSource.string(hocon), name))
      loaded.collect { case Right(c: OpenAICompatibleConfig) => c.baseUrl } shouldBe Seq(
        "http://localhost:8000/v1",
        "https://api.groq.com/openai/v1"
      )
    }

    "reject a reserve that leaves no room for a prompt" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "bad-window") match
        case Left(err) => err.message should include("reserveCompletion must be at least 0 and less than contextWindow")
        case other     => fail(s"Expected a configuration error, got $other")
    }

    "build an OpenAICompatibleClient for the default provider" in {
      Llm4sConfig
        .provider(ConfigSource.string(hocon), "local-vllm")
        .flatMap(cfg => LLMConnect.getClient(cfg)) match
        case Right(client) => client.getClass.getSimpleName shouldBe "OpenAICompatibleClient"
        case Left(err)     => fail(s"Expected a client, got error: ${err.message}")
    }

    "list an openai-compatible endpoint's models without an API key" in {
      val responseBody = """{ "data": [ { "id": "Qwen/Qwen2.5-7B-Instruct", "owned_by": "vllm" } ] }"""
      val httpClient   = new MockHttpClient(HttpResponse(200, responseBody, Map.empty))

      Llm4sConfig.listModels("local-vllm", ConfigSource.string(hocon), httpClient) match
        case Right(models) =>
          models.map(_.name.asString) shouldBe List("Qwen/Qwen2.5-7B-Instruct")
          models.map(_.provider) shouldBe List(OpenAICompatibleProvider.id)
          httpClient.lastUrl shouldBe Some("http://localhost:8000/v1/models")
          httpClient.lastHeaders.getOrElse(Map.empty).keySet should not contain "Authorization"
        case Left(err) =>
          fail(s"Expected listed models, got error: ${err.message}")
    }
  }
