package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig }
import org.llm4s.llmconnect.provider.{ AzureProvider, OpenAIProvider, RequestyProvider }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named `openai`, `azure` and `requesty` provider sections, from validation to a loaded
 * config.
 *
 * The validator cases moved from core's `NamedProviderConfigValidatorSpec` with the
 * providers (#1132); core's config-loading specs that used OpenAI only as a convenient
 * API-key provider now use the test fixture `FixtureChatProvider` instead, and the OpenAI
 * end-to-end cases they covered are here. These resolve the providers through
 * `ProviderRegistry.default`, so they also prove this module's services entry is found.
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
          extras = Map("organization" -> " org-demo ")
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("openai")
          cfg.model.asString shouldBe "gpt-4o-mini"
          cfg.baseUrl.map(_.asUrl) shouldBe Some("https://api.openai.com/v1")
          cfg.apiKey.map(_.asKey) shouldBe Some("sk-test")
          cfg.extra("organization") shouldBe Some("org-demo")
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
          extras = Map("endpoint" -> "https://my-resource.openai.azure.com", "apiVersion" -> "2024-02-01")
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("azure")
          cfg.model.asString shouldBe "gpt-4o"
          cfg.apiKey.map(_.asKey) shouldBe Some("azure-key")
          cfg.extra("endpoint") shouldBe Some("https://my-resource.openai.azure.com")
          cfg.extra("apiVersion") shouldBe Some("2024-02-01")
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
        )
      ) match
        case Left(err) =>
          err.message should include(
            "- apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf"
          )
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
          extras = Map("endpoint" -> "   ")
        )
      ) match
        case Left(err) =>
          err.message should include("- endpoint: the model endpoint/deployment name in your Azure OpenAI resource")
        case Right(cfg) =>
          fail(s"Expected missing Azure endpoint failure, got config: $cfg")
    }

    // `organization` is declared by OpenAI, Requesty and OpenRouter only. Anywhere else it is an
    // unknown key, reported and dropped as any other is - it used to be carried, and ignored, by
    // every provider.
    "report organization on an Azure section as an unknown key and drop it" in {
      val name = ProviderName("azure-main")
      val raw = RawNamedProviderSection(
        provider = Some("azure"),
        model = Some("gpt-4o"),
        baseUrl = None,
        apiKey = Some("azure-key"),
        extras = Map("endpoint" -> "https://x.openai.azure.com", "organization" -> "org-1")
      )

      val (cfg, warnings) = NamedProviderConfigNormalizer
        .normalize(name, raw)
        .flatMap(NamedProviderSectionValidator.validateWithWarnings(name, AzureProvider, _))
        .fold(err => fail(err.message), identity)

      cfg.extra("organization") shouldBe None
      warnings should have size 1
      warnings.head should include("llm4s.providers.azure-main has unknown key(s) organization, which are ignored")
      warnings.head should include("provider = azure also accepts endpoint, apiVersion")
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
          requesty.providerId shouldBe ProviderId("requesty")
        case other =>
          fail(s"Expected OpenAIConfig, got $other")
    }

    "carry a section's organization into the OpenAI and Requesty configs" in {
      val hocon =
        """
          |llm4s.providers {
          |  openai-main {
          |    provider = "openai"
          |    model = "gpt-4o-mini"
          |    apiKey = "k"
          |    organization = "org-1"
          |  }
          |  requesty-main {
          |    provider = "requesty"
          |    model = "openai/gpt-4o-mini"
          |    apiKey = "k"
          |    organization = "org-2"
          |  }
          |  openai-plain {
          |    provider = "openai"
          |    model = "gpt-4o-mini"
          |    apiKey = "k"
          |  }
          |}
          |""".stripMargin

      def organization(name: String) =
        Llm4sConfig.provider(ConfigSource.string(hocon), name) match
          case Right(openai: OpenAIConfig) => openai.organization
          case other                       => fail(s"Expected OpenAIConfig, got $other")

      organization("openai-main") shouldBe Some("org-1")
      organization("requesty-main") shouldBe Some("org-2")
      organization("openai-plain") shouldBe None
    }

    "fail an Azure section with no endpoint, naming the key" in {
      val hocon =
        """
          |llm4s.providers.azure-main {
          |  provider = "azure"
          |  model = "gpt-4o"
          |  apiKey = "azure-key"
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "azure-main") match
        case Left(err) =>
          err.message should include("Provider 'azure-main' (provider = azure) is missing required fields")
          err.message should include("- endpoint:")
          err.message should include("llm4s.providers.azure-main.endpoint")
        case Right(cfg) => fail(s"Expected a missing-endpoint failure, got $cfg")
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
