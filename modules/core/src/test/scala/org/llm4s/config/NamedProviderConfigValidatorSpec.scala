package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Named provider sections: the validation that does not depend on the provider.
 *
 * The OpenAI and Azure cases moved to `llm4s-openai`'s `OpenAINamedProviderSpec` with the
 * providers (#1132), as the Anthropic and Gemini cases did before them, and the OpenRouter, DeepSeek
 * and Z.ai cases - and, when they moved onto the shared client, Mistral's and Cohere's - to
 * `llm4s-openai-compatible`'s `OpenAICompatibleNamedProviderSpec`. Core ships no provider.
 */
class NamedProviderConfigValidatorSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

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
