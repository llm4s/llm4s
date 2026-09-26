package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig }
import org.llm4s.llmconnect.provider.{ OpenAIProvider, RequestyProvider }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named `openai`, `azure` and `requesty` provider sections, from validation to a loaded
 * config.
 *
 * The validator cases moved from core's `NamedProviderConfigValidatorSpec` with the
 * providers (#1132); core's config-loading specs that used OpenAI only as a convenient
 * API-key provider now use DeepSeek instead, and the OpenAI end-to-end cases they covered
 * are here. These resolve the providers through `ProviderRegistry.default`, so they also
 * prove this module's services entry is found.
 */
class OpenAINamedProviderSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

    "validate and normalize an OpenAI named provider section" in {
      validate(
        "openai-main",
        RawNamedProviderSection(
          provider = Some(" openai "),
          model = Some(" gpt-4o-mini "),
          baseUrl = Some(" https://api.openai.com/v1 "),
          apiKey = Some(" sk-test "),
          organization = Some(" org-demo "),
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("openai")
          cfg.model.asString shouldBe "gpt-4o-mini"
          cfg.baseUrl.map(_.asUrl) shouldBe Some("https://api.openai.com/v1")
          cfg.apiKey.map(_.asKey) shouldBe Some("sk-test")
          cfg.organization shouldBe Some("org-demo")
        case Left(err) =>
          fail(s"Expected OpenAI NamedProviderConfig, got error: ${err.message}")
    }

    "validate and normalize an Azure named provider section" in {
      validate(
        "azure-main",
        RawNamedProviderSection(
          provider = Some("azure"),
          model = Some("gpt-4o"),
          baseUrl = None,
          apiKey = Some("azure-key"),
          organization = None,
          endpoint = Some("https://my-resource.openai.azure.com"),
          apiVersion = Some("2024-02-01"),
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("azure")
          cfg.model.asString shouldBe "gpt-4o"
          cfg.apiKey.map(_.asKey) shouldBe Some("azure-key")
          cfg.endpoint shouldBe Some("https://my-resource.openai.azure.com")
          cfg.apiVersion shouldBe Some("2024-02-01")
        case Left(err) =>
          fail(s"Expected Azure NamedProviderConfig, got error: ${err.message}")
    }

    "fail clearly when OpenAI apiKey is missing" in {
      validate(
        "openai-main",
        RawNamedProviderSection(
          provider = Some("openai"),
          model = Some("gpt-4o-mini"),
          baseUrl = None,
          apiKey = Some("   "),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("- apiKey: set it in llm4s.conf under providers.openai-main.apiKey")
        case Right(cfg) =>
          fail(s"Expected missing OpenAI apiKey failure, got config: $cfg")
    }

    "fail clearly when Azure endpoint is missing" in {
      validate(
        "azure-main",
        RawNamedProviderSection(
          provider = Some("azure"),
          model = Some("gpt-4o"),
          baseUrl = None,
          apiKey = Some("azure-key"),
          organization = None,
          endpoint = Some("   "),
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("- endpoint: the model endpoint/deployment name in your Azure OpenAI resource")
        case Right(cfg) =>
          fail(s"Expected missing Azure endpoint failure, got config: $cfg")
    }
  }

  "Llm4sConfig" should {

    "load an OpenAI named provider as the default, defaulting the base URL" in {
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

      Llm4sConfig.defaultProvider(ConfigSource.string(hocon)) match
        case Right(openai: OpenAIConfig) =>
          openai.model shouldBe "gpt-4o-mini"
          openai.apiKey shouldBe "named-openai-key"
          openai.baseUrl shouldBe OpenAIProvider.DEFAULT_BASE_URL
        case other =>
          fail(s"Expected OpenAIConfig, got $other")
    }

    "load a Requesty named provider, defaulting the base URL to the Requesty router" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    requesty-main {
          |      provider = "requesty"
          |      model = "openai/gpt-4o-mini"
          |      apiKey = "rq-key"
          |    }
          |  }
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "requesty-main") match
        case Right(requesty: OpenAIConfig) =>
          requesty.baseUrl shouldBe RequestyProvider.DEFAULT_BASE_URL
          requesty.providerId shouldBe ProviderId("openai")
        case other =>
          fail(s"Expected OpenAIConfig, got $other")
    }

    "load an Azure named provider, defaulting the API version" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    azure-main {
          |      provider = "azure"
          |      model = "gpt-4o"
          |      apiKey = "azure-key"
          |      endpoint = "https://my-resource.openai.azure.com"
          |    }
          |  }
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "azure-main") match
        case Right(azure: AzureConfig) =>
          azure.endpoint shouldBe "https://my-resource.openai.azure.com"
          azure.apiVersion shouldBe AzureConfig.DEFAULT_API_VERSION
        case other =>
          fail(s"Expected AzureConfig, got $other")
    }

    "list models for a configured OpenAI named provider by name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "sk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val responseBody =
        """{
          |  "data": [
          |    { "id": "gpt-4o-mini", "created": 1710000000, "owned_by": "openai" }
          |  ]
          |}""".stripMargin

      val httpClient = new MockHttpClient(HttpResponse(200, responseBody, Map.empty))

      Llm4sConfig.listModels("openai-main", ConfigSource.string(hocon), httpClient) match
        case Right(models) =>
          models.map(_.name.asString) shouldBe List("gpt-4o-mini")
          models.map(_.provider) shouldBe List(ProviderId("openai"))
          httpClient.lastUrl shouldBe Some(s"${OpenAIProvider.DEFAULT_BASE_URL}/models")
        case Left(err) =>
          fail(s"Expected listed models, got error: ${err.message}")
    }
  }
