package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.llmconnect.config.{ GeminiConfig, VertexAIConfig }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named `gemini` and `vertexai` provider sections, from validation to a loaded config.
 *
 * Moved from core's `NamedProviderConfigValidatorSpec` and `Llm4sConfigProviderSpec` with
 * the providers (#1132). These resolve `gemini`, `vertexai` and their aliases through
 * `ProviderRegistry.default`, so they also prove this module's services entry is found.
 */
class GeminiNamedProviderSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

    "validate and normalize a Gemini named provider section" in {
      validate(
        "gemini-main",
        RawNamedProviderSection(
          provider = Some("gemini"),
          model = Some("gemini-2.5-flash"),
          baseUrl = Some("https://generativelanguage.googleapis.com/v1beta"),
          apiKey = Some("google-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("gemini")
          cfg.model.asString shouldBe "gemini-2.5-flash"
          cfg.apiKey.map(_.asKey) shouldBe Some("google-key")
        case Left(err) =>
          fail(s"Expected Gemini NamedProviderConfig, got error: ${err.message}")
    }

    "accept the 'google' alias for Gemini" in {
      validate(
        "google-alias",
        RawNamedProviderSection(
          provider = Some("Google"),
          model = Some("gemini-2.5-flash"),
          baseUrl = None,
          apiKey = Some("google-key"),
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) => cfg.provider shouldBe ProviderId("gemini")
        case Left(err)  => fail(s"Expected the 'google' alias to resolve, got error: ${err.message}")
    }

    "fail clearly when a Gemini section omits the apiKey" in {
      validate(
        "broken-gemini",
        RawNamedProviderSection(
          provider = Some("gemini"),
          model = Some("gemini-2.5-flash"),
          baseUrl = None,
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("Provider 'broken-gemini' (provider = gemini) is missing required fields")
          err.message should include("- apiKey: set it in llm4s.conf under providers.broken-gemini.apiKey")
        case Right(cfg) =>
          fail(s"Expected a missing-apiKey failure, got config: $cfg")
    }

    // Regression for the gap found while scoping #1131: `vertexai` was a supported provider in
    // NamedProviderLoader and LLMConnect, but had no capabilities entry and no validator, so
    // validation rejected it outright and it could never be reached from config at all.
    "validate and normalize a Vertex AI named provider section" in {
      validate(
        "vertex-main",
        RawNamedProviderSection(
          provider = Some("vertexai"),
          model = Some("gemini-2.0-flash"),
          baseUrl = None,
          apiKey = Some("/path/to/credentials.json"),
          organization = Some("europe-west4"),
          endpoint = Some("my-gcp-project"),
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("vertexai")
          cfg.model.asString shouldBe "gemini-2.0-flash"
          cfg.endpoint shouldBe Some("my-gcp-project")
          cfg.organization shouldBe Some("europe-west4")
        case Left(err) =>
          fail(s"Expected Vertex AI NamedProviderConfig, got error: ${err.message}")
    }

    "accept the 'vertex' alias for Vertex AI" in {
      validate(
        "vertex-alias",
        RawNamedProviderSection(
          provider = Some("Vertex"),
          model = Some("gemini-2.0-flash"),
          baseUrl = None,
          apiKey = None,
          organization = None,
          endpoint = Some("my-gcp-project"),
          apiVersion = None,
        )
      ) match
        case Right(cfg) => cfg.provider shouldBe ProviderId("vertexai")
        case Left(err)  => fail(s"Expected the 'vertex' alias to resolve, got error: ${err.message}")
    }

    "fail clearly when a Vertex AI section omits the GCP project id" in {
      validate(
        "vertex-missing",
        RawNamedProviderSection(
          provider = Some("vertexai"),
          model = Some("gemini-2.0-flash"),
          baseUrl = None,
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("Provider 'vertex-missing' (provider = vertexai) is missing required fields")
          err.message should include("- endpoint: the GCP project ID that owns your Vertex AI resources")
        case Right(cfg) =>
          fail(s"Expected a missing-endpoint failure, got config: $cfg")
    }
  }

  "Llm4sConfig" should {

    "load a Gemini named provider end to end, defaulting the base URL" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    gemini-main {
          |      provider = "google"
          |      model = "gemini-2.0-flash"
          |      apiKey = "google-key"
          |    }
          |  }
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "gemini-main") match
        case Right(gemini: GeminiConfig) =>
          gemini.model shouldBe "gemini-2.0-flash"
          gemini.apiKey shouldBe "google-key"
          gemini.baseUrl shouldBe GeminiConfig.DEFAULT_BASE_URL
        case other =>
          fail(s"Expected GeminiConfig, got $other")
    }

    // Regression for the #1131 scoping finding: before the capabilities registry gained a
    // `vertexai` entry, this HOCON failed validation outright, even though NamedProviderLoader
    // and LLMConnect had supported Vertex AI all along.
    "load a Vertex AI named provider end to end" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    vertex-main {
          |      provider = "vertexai"
          |      model = "gemini-2.0-flash"
          |      endpoint = "my-gcp-project"
          |      organization = "europe-west4"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg =
        Llm4sConfig.provider(ConfigSource.string(hocon), "vertex-main").fold(err => fail(err.toString), identity)

      cfg match
        case vertex: VertexAIConfig =>
          vertex.projectId shouldBe "my-gcp-project"
          vertex.location shouldBe "europe-west4"
          vertex.model shouldBe "gemini-2.0-flash"
          vertex.providerId shouldBe ProviderId("vertexai")
          vertex.endpointUrl shouldBe Some("https://europe-west4-aiplatform.googleapis.com/v1")
        case other =>
          fail(s"Expected VertexAIConfig, got $other")
    }

    "default the Vertex AI region when a section sets no organization" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    vertex-default {
          |      provider = "vertex"
          |      model = "gemini-2.0-flash"
          |      endpoint = "my-gcp-project"
          |    }
          |  }
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "vertex-default") match
        case Right(vertex: VertexAIConfig) =>
          vertex.location shouldBe VertexAIConfig.DEFAULT_LOCATION
          vertex.credentialFilePath shouldBe None
        case other =>
          fail(s"Expected VertexAIConfig, got $other")
    }
  }
