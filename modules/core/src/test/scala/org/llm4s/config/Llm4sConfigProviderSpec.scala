package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ AnthropicConfig, DeepSeekConfig, GeminiConfig, MistralConfig, OpenAIConfig }
import org.llm4s.llmconnect.provider.LLMProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

class Llm4sConfigProviderSpec extends AnyWordSpec with Matchers:

  "Llm4sConfig.provider" should {
    "load OpenAI config from llm4s.* defaults" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai { apiKey = "test-key" }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.provider(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg match
        case openai: OpenAIConfig =>
          openai.model shouldBe "gpt-4o"
          openai.apiKey shouldBe "test-key"
        case other =>
          fail(s"Expected OpenAIConfig, got $other")
    }

    "load Anthropic config via HOCON" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "anthropic/claude-3-5-sonnet-latest" }
          |  anthropic { apiKey = "sk-ant-test-key" }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.provider(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg match
        case anthropic: AnthropicConfig =>
          anthropic.model shouldBe "claude-3-5-sonnet-latest"
          anthropic.apiKey shouldBe "sk-ant-test-key"
          anthropic.baseUrl shouldBe DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL
        case other =>
          fail(s"Expected AnthropicConfig, got $other")
    }

    "load DeepSeek config via HOCON" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "deepseek/deepseek-chat" }
          |  deepseek { apiKey = "ds-test-key" }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.provider(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg match
        case deepseek: DeepSeekConfig =>
          deepseek.model shouldBe "deepseek-chat"
          deepseek.apiKey shouldBe "ds-test-key"
          deepseek.baseUrl shouldBe DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL
        case other =>
          fail(s"Expected DeepSeekConfig, got $other")
    }

    "load Mistral config via HOCON" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "mistral/mistral-large-latest" }
          |  mistral { apiKey = "mistral-test-key" }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.provider(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg match
        case mistral: MistralConfig =>
          mistral.model shouldBe "mistral-large-latest"
          mistral.apiKey shouldBe "mistral-test-key"
          mistral.baseUrl shouldBe MistralConfig.DEFAULT_BASE_URL
        case other =>
          fail(s"Expected MistralConfig, got $other")
    }

    "return ConfigurationError for blank model" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "   " }
          |  openai { apiKey = "test-key" }
          |}
          |""".stripMargin

      val result = Llm4sConfig.provider(ConfigSource.string(hocon))
      result.isLeft shouldBe true
      result.left.getOrElse(fail("expected Left")).message should include("Missing model spec")
    }

    "load a named provider directly by name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    deepseek-main {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "deepseek-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg =
        Llm4sConfig.provider(ConfigSource.string(hocon), "deepseek-main").fold(err => fail(err.toString), identity)

      cfg match
        case deepseek: DeepSeekConfig =>
          deepseek.model shouldBe "deepseek-chat"
          deepseek.apiKey shouldBe "deepseek-key"
          deepseek.baseUrl shouldBe DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL
        case other =>
          fail(s"Expected DeepSeekConfig, got $other")
    }

    "fail when a sibling named provider is invalid even if the requested provider is valid" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |    broken-gemini {
          |      provider = "gemini"
          |      model = "gemini-2.5-flash"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = Llm4sConfig.provider(ConfigSource.string(hocon), "openai-main")

      result match
        case Left(err) =>
          err.message should include("Configured provider 'broken-gemini'")
          err.message should include("missing required field `apiKey`")
        case Right(cfg) =>
          fail(s"Expected invalid sibling named provider to fail whole config, got config: $cfg")
    }
  }

  "Llm4sConfig named providers" should {
    "load the full validated providers config" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |    gemini-main {
          |      provider = "gemini"
          |      model = "gemini-2.5-flash"
          |      apiKey = "google-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.providers(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg.selectedProvider shouldBe Some(ProviderName("openai-main"))
      cfg.namedProviders.keySet.map(_.asName) shouldBe Set("openai-main", "gemini-main")
    }

    "load the configured default provider name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val providerName =
        Llm4sConfig.defaultProviderName(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)
      providerName shouldBe ProviderName("openai-main")
    }

    "load the configured default provider as ProviderConfig" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.defaultProvider(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg match
        case openai: OpenAIConfig =>
          openai.model shouldBe "gpt-4o-mini"
          openai.apiKey shouldBe "named-openai-key"
          openai.baseUrl shouldBe DefaultConfig.DEFAULT_OPENAI_BASE_URL
        case other =>
          fail(s"Expected OpenAIConfig, got $other")
    }

    "list models for a configured named provider by name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "ollama-main"
          |    ollama-main {
          |      provider = "ollama"
          |      model = "llama3.1"
          |      baseUrl = "http://localhost:11434"
          |    }
          |  }
          |}
          |""".stripMargin

      val responseBody =
        """{
          |  "models": [
          |    {
          |      "name": "llama3.2:latest",
          |      "modified_at": "2026-03-27T08:00:00Z",
          |      "size": 2019393189,
          |      "digest": "sha256:abc123"
          |    }
          |  ]
          |}""".stripMargin

      val httpClient = new MockHttpClient(HttpResponse(200, responseBody, Map.empty))

      val result = Llm4sConfig.listModels("ollama-main", ConfigSource.string(hocon), httpClient)

      result match
        case Right(models) =>
          models.map(_.name.asString) shouldBe List("llama3.2:latest")
          models.map(_.provider) shouldBe List(LLMProvider.Ollama)
          httpClient.lastUrl shouldBe Some("http://localhost:11434/api/tags")
        case Left(err) =>
          fail(s"Expected listed models, got error: ${err.message}")
    }

    "fail clearly when listing models for a missing named provider" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "ollama-main"
          |    ollama-main {
          |      provider = "ollama"
          |      model = "llama3.1"
          |      baseUrl = "http://localhost:11434"
          |    }
          |  }
          |}
          |""".stripMargin

      val result =
        Llm4sConfig.listModels(
          "ollmaa-main",
          ConfigSource.string(hocon),
          new MockHttpClient(HttpResponse(200, "{}", Map.empty))
        )

      result match
        case Left(err) =>
          err.message should include("Configured provider 'ollmaa-main' was not found")
        case Right(models) =>
          fail(s"Expected missing named provider failure, got models: $models")
    }

    "fail when requesting the default provider name and no default is configured" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = Llm4sConfig.defaultProviderName(ConfigSource.string(hocon))
      result.isLeft shouldBe true
      result.left.getOrElse(fail("expected Left")).message should include(
        "No default provider configured under llm4s.providers.provider"
      )
    }

    "fail when requesting the default provider and no default is configured" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = Llm4sConfig.defaultProvider(ConfigSource.string(hocon))
      result.isLeft shouldBe true
      result.left.getOrElse(fail("expected Left")).message should include(
        "No default provider configured under llm4s.providers.provider"
      )
    }

    "allow legacy and new provider configs to coexist in the same configuration" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  openai { apiKey = "legacy-openai-key" }
          |  providers {
          |    provider = "gemini-main"
          |    gemini-main {
          |      provider = "gemini"
          |      model = "gemini-2.5-flash"
          |      apiKey = "google-key"
          |    }
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "named-openai-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val source = ConfigSource.string(hocon)

      val legacyCfg           = Llm4sConfig.provider(source).fold(err => fail(err.toString), identity)
      val providersCfg        = Llm4sConfig.providers(source).fold(err => fail(err.toString), identity)
      val defaultProviderName = Llm4sConfig.defaultProviderName(source).fold(err => fail(err.toString), identity)
      val defaultProviderCfg  = Llm4sConfig.defaultProvider(source).fold(err => fail(err.toString), identity)
      val namedProviderCfg    = Llm4sConfig.provider(source, "openai-main").fold(err => fail(err.toString), identity)

      legacyCfg match
        case openai: OpenAIConfig =>
          openai.model shouldBe "gpt-4o"
          openai.apiKey shouldBe "legacy-openai-key"
        case other =>
          fail(s"Expected legacy OpenAIConfig, got $other")

      providersCfg.selectedProvider shouldBe Some(ProviderName("gemini-main"))
      providersCfg.namedProviders.keySet.map(_.asName) shouldBe Set("gemini-main", "openai-main")

      defaultProviderName shouldBe ProviderName("gemini-main")

      defaultProviderCfg match
        case gemini: GeminiConfig =>
          gemini.model shouldBe "gemini-2.5-flash"
          gemini.apiKey shouldBe "google-key"
        case other =>
          fail(s"Expected default GeminiConfig, got $other")

      namedProviderCfg match
        case openai: OpenAIConfig =>
          openai.model shouldBe "gpt-4o-mini"
          openai.apiKey shouldBe "named-openai-key"
        case other =>
          fail(s"Expected named OpenAIConfig, got $other")
    }
  }
