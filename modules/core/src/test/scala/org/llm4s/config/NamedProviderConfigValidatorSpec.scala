package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NamedProviderConfigValidatorSpec extends AnyWordSpec with Matchers:

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

    "validate and normalize an OpenRouter named provider section" in {
      validate(
        "openrouter-main",
        RawNamedProviderSection(
          provider = Some("openrouter"),
          model = Some("openai/gpt-4o-mini"),
          baseUrl = Some("https://openrouter.ai/api/v1"),
          apiKey = Some("or-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("openrouter")
          cfg.model.asString shouldBe "openai/gpt-4o-mini"
          cfg.apiKey.map(_.asKey) shouldBe Some("or-key")
        case Left(err) =>
          fail(s"Expected OpenRouter NamedProviderConfig, got error: ${err.message}")
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

    "validate and normalize a Z.ai named provider section" in {
      validate(
        "zai-main",
        RawNamedProviderSection(
          provider = Some("zai"),
          model = Some("GLM-4.7"),
          baseUrl = Some("https://api.z.ai/api/paas/v4"),
          apiKey = Some("zai-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("zai")
          cfg.model.asString shouldBe "GLM-4.7"
          cfg.apiKey.map(_.asKey) shouldBe Some("zai-key")
        case Left(err) =>
          fail(s"Expected Z.ai NamedProviderConfig, got error: ${err.message}")
    }

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

    "validate and normalize a Cohere named provider section" in {
      validate(
        "cohere-main",
        RawNamedProviderSection(
          provider = Some("cohere"),
          model = Some("command-r-plus"),
          baseUrl = Some("https://api.cohere.com"),
          apiKey = Some("cohere-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("cohere")
          cfg.model.asString shouldBe "command-r-plus"
          cfg.apiKey.map(_.asKey) shouldBe Some("cohere-key")
        case Left(err) =>
          fail(s"Expected Cohere NamedProviderConfig, got error: ${err.message}")
    }

    "validate and normalize a Mistral named provider section" in {
      validate(
        "mistral-main",
        RawNamedProviderSection(
          provider = Some("mistral"),
          model = Some("mistral-large-latest"),
          baseUrl = Some("https://api.mistral.ai"),
          apiKey = Some("mistral-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("mistral")
          cfg.model.asString shouldBe "mistral-large-latest"
          cfg.apiKey.map(_.asKey) shouldBe Some("mistral-key")
        case Left(err) =>
          fail(s"Expected Mistral NamedProviderConfig, got error: ${err.message}")
    }

    "fail clearly when provider field is missing" in {
      validate(
        "broken",
        RawNamedProviderSection(
          provider = None,
          model = Some("gpt-4o-mini"),
          baseUrl = None,
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("missing required field `provider`")
        case Right(cfg) =>
          fail(s"Expected provider validation failure, got config: $cfg")
    }

    // Unknown ids are no longer rejected while *parsing* - they parse to a ProviderId and fail at
    // resolution, which is what lets a provider ship in its own module (#1131). The error must
    // still name what went wrong and what is available.
    "fail clearly when the provider id cannot be resolved" in {
      validate(
        "weird",
        RawNamedProviderSection(
          provider = Some("moonbeam"),
          model = Some("v1"),
          baseUrl = None,
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("'moonbeam'")
          err.message should include("Registered providers:")
          err.message should include("openai")
        case Right(cfg) =>
          fail(s"Expected unresolvable provider failure, got config: $cfg")
    }

    "fail clearly when model field is missing" in {
      validate(
        "openai-main",
        RawNamedProviderSection(
          provider = Some("openai"),
          model = Some("   "),
          baseUrl = None,
          apiKey = Some("sk-test"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("missing required field `model`")
        case Right(cfg) =>
          fail(s"Expected missing model validation failure, got config: $cfg")
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
