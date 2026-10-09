package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.provider.{ DeepSeekProvider, OpenAICompatibleProvider }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named provider sections for the providers `llm4s-openai-compatible` holds.
 *
 * The DeepSeek, Z.ai, OpenRouter, Mistral and Cohere cases are core's `NamedProviderConfigValidatorSpec`
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
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("deepseek")
          cfg.model.asString shouldBe "deepseek-chat"
          cfg.apiKey.map(_.asKey) shouldBe Some("deepseek-key")
        case Left(err) =>
          fail(s"Expected DeepSeek NamedProviderConfig, got error: ${err.message}")
    }

    "validate and normalize an OpenRouter named provider section" in {
      validate(
        "openrouter-main",
        RawNamedProviderSection(
          provider = Some("openrouter"),
          model = Some("openai/gpt-4o-mini"),
          baseUrl = Some("https://openrouter.ai/api/v1"),
          apiKey = Some("or-key"),
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("openrouter")
          cfg.model.asString shouldBe "openai/gpt-4o-mini"
          cfg.apiKey.map(_.asKey) shouldBe Some("or-key")
        case Left(err) =>
          fail(s"Expected OpenRouter NamedProviderConfig, got error: ${err.message}")
    }

    "validate and normalize a Z.ai named provider section" in {
      validate(
        "zai-main",
        RawNamedProviderSection(
          provider = Some("zai"),
          model = Some("GLM-4.7"),
          baseUrl = Some("https://api.z.ai/api/paas/v4"),
          apiKey = Some("zai-key"),
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("zai")
          cfg.model.asString shouldBe "GLM-4.7"
          cfg.apiKey.map(_.asKey) shouldBe Some("zai-key")
        case Left(err) =>
          fail(s"Expected Z.ai NamedProviderConfig, got error: ${err.message}")
    }

    "validate and normalize a Mistral named provider section" in {
      validate(
        "mistral-main",
        RawNamedProviderSection(
          provider = Some("mistral"),
          model = Some("mistral-large-latest"),
          baseUrl = Some("https://api.mistral.ai"),
          apiKey = Some("mistral-key"),
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("mistral")
          cfg.model.asString shouldBe "mistral-large-latest"
          cfg.apiKey.map(_.asKey) shouldBe Some("mistral-key")
        case Left(err) =>
          fail(s"Expected Mistral NamedProviderConfig, got error: ${err.message}")
    }

    "validate and normalize a Cohere named provider section" in {
      validate(
        "cohere-main",
        RawNamedProviderSection(
          provider = Some("cohere"),
          model = Some("command-r-plus"),
          baseUrl = Some("https://api.cohere.com"),
          apiKey = Some("cohere-key"),
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("cohere")
          cfg.model.asString shouldBe "command-r-plus"
          cfg.apiKey.map(_.asKey) shouldBe Some("cohere-key")
        case Left(err) =>
          fail(s"Expected Cohere NamedProviderConfig, got error: ${err.message}")
    }

    "accept an openai-compatible section with no apiKey" in {
      validate(
        "local-vllm",
        RawNamedProviderSection(
          provider = Some("openai-compatible"),
          model = Some("qwen"),
          baseUrl = Some("http://localhost:8000/v1"),
          apiKey = None,
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
        )
      ).left.toOption.getOrElse(fail("Expected a missing-baseUrl failure")).message

      message should include("Provider 'no-url' (provider = openai-compatible) is missing required fields")
      message should include(
        "baseUrl: set it in application.conf under llm4s.providers.no-url.baseUrl (e.g. http://localhost:8000/v1; " +
          "to read it from OPENAI_COMPATIBLE_BASE_URL, add baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL} to the section)"
      )
      // The variable is shown as a binding, never as "set OPENAI_COMPATIBLE_BASE_URL": nothing reads it unbound.
      (message should not).include("or set")
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
      |      model = "openai/gpt-oss-120b"
      |      apiKey = "gsk-test"
      |      contextWindow = 131072
      |      reserveCompletion = 8192
      |      headers {
      |        X-Team = "search"
      |        X-Gateway-Token = "secret-token"
      |      }
      |    }
      |    no-usage {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      streamUsage = false
      |    }
      |    off-usage {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      streamUsage = off
      |    }
      |    bad-usage {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      streamUsage = "sometimes"
      |    }
      |    bad-window {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      contextWindow = 1000
      |      reserveCompletion = 1000
      |    }
      |    text-window {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      contextWindow = "lots"
      |    }
      |    zero-window {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      contextWindow = 0
      |    }
      |    negative-reserve {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      reserveCompletion = -1
      |    }
      |    zero-reserve {
      |      provider = "openai-compatible"
      |      baseUrl = "http://localhost:8000/v1"
      |      model = "m"
      |      contextWindow = 4096
      |      reserveCompletion = 0
      |    }
      |    deepseek-window {
      |      provider = "deepseek"
      |      model = "deepseek-chat"
      |      apiKey = "k"
      |      contextWindow = 4096
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
          cfg.streamUsage shouldBe true
        case other =>
          fail(s"Expected OpenAICompatibleConfig, got $other")
    }

    "turn streamed usage off when a section sets streamUsage = false" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "no-usage") match
        case Right(cfg: OpenAICompatibleConfig) => cfg.streamUsage shouldBe false
        case other                              => fail(s"Expected OpenAICompatibleConfig, got $other")
    }

    "read streamUsage with HOCON's boolean spellings" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "off-usage") match
        case Right(cfg: OpenAICompatibleConfig) => cfg.streamUsage shouldBe false
        case other                              => fail(s"Expected OpenAICompatibleConfig, got $other")
    }

    "reject a streamUsage that is not a boolean, naming the key and the section" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "bad-usage") match
        case Left(err) =>
          err.message should include("Configured provider 'bad-usage' has an invalid streamUsage")
          err.message should include("llm4s.providers.bad-usage.streamUsage must be true or false, got 'sometimes'")
        case other => fail(s"Expected a configuration error, got $other")
    }

    "round-trip streamUsage from HOCON through the declared key, with no unknown-key warning" in {
      val section = ProvidersConfigLoader
        .load(ConfigSource.string(hocon))
        .fold(e => fail(e.message), identity)
        .namedProviders
      section(ProviderName("no-usage")).extra(OpenAICompatibleProvider.StreamUsageKey) shouldBe Some("false")
      // The declared default fills a section that omits the key.
      section(ProviderName("local-vllm")).extra(OpenAICompatibleProvider.StreamUsageKey) shouldBe Some("true")

      val raw  = RawProvidersConfigLoader.load(ConfigSource.string(hocon)).fold(e => fail(e.message), identity)
      val name = ProviderName("no-usage")
      val warnings = NamedProviderConfigNormalizer
        .normalize(name, raw.namedProviders(name))
        .flatMap(NamedProviderSectionValidator.validateWithWarnings(name, OpenAICompatibleProvider, _))
        .fold(e => fail(e.message), _._2)
      warnings shouldBe empty
    }

    "load the key, context window, reserve and headers a section sets" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "groq-main") match
        case Right(cfg: OpenAICompatibleConfig) =>
          cfg.model shouldBe "openai/gpt-oss-120b"
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

    // contextWindow and reserveCompletion are this provider's declared keys since #1133, not
    // fields of NamedProviderConfig: they arrive as strings, so the descriptor parses them.
    "reject a contextWindow that is not a whole number, naming the key and the section" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "text-window") match
        case Left(err) =>
          err.message shouldBe "Configured provider 'text-window' has an invalid contextWindow: " +
            "llm4s.providers.text-window.contextWindow must be a positive whole number, got 'lots'"
        case other => fail(s"Expected a configuration error, got $other")
    }

    "reject a contextWindow of zero" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "zero-window") match
        case Left(err) => err.message should include("contextWindow must be a positive whole number, got '0'")
        case other     => fail(s"Expected a configuration error, got $other")
    }

    "reject a negative reserveCompletion" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "negative-reserve") match
        case Left(err) =>
          err.message should include(
            "llm4s.providers.negative-reserve.reserveCompletion must be a whole number, 0 or more"
          )
        case other => fail(s"Expected a configuration error, got $other")
    }

    "accept a reserveCompletion of zero" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "zero-reserve") match
        case Right(cfg: OpenAICompatibleConfig) =>
          cfg.contextWindow shouldBe 4096
          cfg.reserveCompletion shouldBe 0
        case other => fail(s"Expected OpenAICompatibleConfig, got $other")
    }

    "read contextWindow and reserveCompletion as declared keys, with no unknown-key warning" in {
      val raw  = RawProvidersConfigLoader.load(ConfigSource.string(hocon)).fold(e => fail(e.message), identity)
      val name = ProviderName("groq-main")
      val (cfg, warnings) = NamedProviderConfigNormalizer
        .normalize(name, raw.namedProviders(name))
        .flatMap(NamedProviderSectionValidator.validateWithWarnings(name, OpenAICompatibleProvider, _))
        .fold(e => fail(e.message), identity)

      cfg.extra(OpenAICompatibleProvider.ContextWindowKey) shouldBe Some("131072")
      cfg.extra(OpenAICompatibleProvider.ReserveCompletionKey) shouldBe Some("8192")
      warnings shouldBe empty
    }

    "report contextWindow on a provider that does not declare it as an unknown key" in {
      val raw  = RawProvidersConfigLoader.load(ConfigSource.string(hocon)).fold(e => fail(e.message), identity)
      val name = ProviderName("deepseek-window")
      val (cfg, warnings) = NamedProviderConfigNormalizer
        .normalize(name, raw.namedProviders(name))
        .flatMap(NamedProviderSectionValidator.validateWithWarnings(name, DeepSeekProvider, _))
        .fold(e => fail(e.message), identity)

      cfg.extras shouldBe empty
      warnings shouldBe Seq(
        "llm4s.providers.deepseek-window has unknown key(s) contextWindow, which are ignored. Besides the " +
          "built-in fields (apiKey, baseUrl, headers, model, provider, timeouts), provider = deepseek declares no " +
          "provider-specific keys."
      )
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
