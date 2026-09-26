package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Named provider sections, validated against the providers core ships.
 *
 * The OpenAI and Azure cases moved to `llm4s-openai`'s `OpenAINamedProviderSpec` with the
 * providers (#1132), as the Anthropic and Gemini cases did before them, and the DeepSeek
 * and Z.ai cases to `llm4s-openai-compatible`'s `OpenAICompatibleNamedProviderSpec`.
 */
class NamedProviderConfigValidatorSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

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
          err.message should include("fixturechat")
        case Right(cfg) =>
          fail(s"Expected unresolvable provider failure, got config: $cfg")
    }

    "fail clearly when model field is missing" in {
      validate(
        "fixturechat-main",
        RawNamedProviderSection(
          provider = Some("fixturechat"),
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

  }
