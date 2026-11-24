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
          apiKey = Some(ApiKey("google-key")),
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
          apiKey = Some(ApiKey("google-key")),
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
        )
      ) match
        case Left(err) =>
          err.message should include("Provider 'broken-gemini' (provider = gemini) is missing required fields")
          err.message should include(
            "- apiKey: set GOOGLE_API_KEY or GEMINI_API_KEY, or set apiKey under llm4s.providers.broken-gemini"
          )
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
          apiKey = Some(ApiKey("/path/to/credentials.json")),
          extras = Map("project" -> "my-gcp-project", "location" -> "europe-west4")
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("vertexai")
          cfg.model.asString shouldBe "gemini-2.0-flash"
          cfg.extras shouldBe Map("project" -> "my-gcp-project", "location" -> "europe-west4")
        case Left(err) =>
          fail(s"Expected Vertex AI NamedProviderConfig, got error: ${err.message}")
    }

    "default the Vertex AI location as a provider-specific key" in {
      validate(
        "vertex-default",
        RawNamedProviderSection(
          provider = Some("vertexai"),
          model = Some("gemini-2.0-flash"),
          baseUrl = None,
          apiKey = None,
          extras = Map("project" -> "my-gcp-project")
        )
      ).map(_.extra("location")) shouldBe Right(Some(VertexAIConfig.DEFAULT_LOCATION))
    }

    // #1215 renamed Vertex's `endpoint` and `organization` to `project` and `location`. The old names
    // are no longer read: they are unknown keys, reported with a warning, and without `project` the
    // section is missing a required key.
    "no longer read the old endpoint/organization keys" in {
      val name = ProviderName("vertex-legacy")
      val raw = RawNamedProviderSection(
        provider = Some("vertexai"),
        model = Some("gemini-2.0-flash"),
        baseUrl = None,
        apiKey = None,
        extras = Map("organization" -> "europe-west4", "endpoint" -> "my-gcp-project")
      )

      val message = NamedProviderConfigNormalizer
        .normalize(name, raw)
        .flatMap(NamedProviderSectionValidator.validate(name, org.llm4s.llmconnect.provider.VertexAIProvider, _))
        .left
        .toOption
        .getOrElse(fail("Expected a missing-project failure"))
        .message

      message should include("- project: the GCP project ID that owns your Vertex AI resources")
      message should include("llm4s.providers.vertex-legacy.project")
    }

    "warn that the old endpoint/organization keys are unknown and ignored" in {
      val name = ProviderName("vertex-renamed")
      val raw = RawNamedProviderSection(
        provider = Some("vertexai"),
        model = Some("gemini-2.0-flash"),
        baseUrl = None,
        apiKey = None,
        extras = Map("project" -> "my-gcp-project", "organization" -> "europe-west4", "endpoint" -> "old")
      )

      val (cfg, warnings) = NamedProviderConfigNormalizer
        .normalize(name, raw)
        .flatMap(
          NamedProviderSectionValidator
            .validateWithWarnings(name, org.llm4s.llmconnect.provider.VertexAIProvider, _)
        )
        .fold(err => fail(err.message), identity)

      cfg.extras shouldBe Map("project" -> "my-gcp-project", "location" -> VertexAIConfig.DEFAULT_LOCATION)
      warnings should have size 1
      warnings.head should startWith(
        "llm4s.providers.vertex-renamed has unknown key(s) endpoint, organization, which are ignored."
      )
      warnings.head should include("provider = vertexai also accepts project, location")
    }

    "accept the 'vertex' alias for Vertex AI" in {
      validate(
        "vertex-alias",
        RawNamedProviderSection(
          provider = Some("Vertex"),
          model = Some("gemini-2.0-flash"),
          baseUrl = None,
          apiKey = None,
          extras = Map("project" -> "my-gcp-project")
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
        )
      ) match
        case Left(err) =>
          err.message should include("Provider 'vertex-missing' (provider = vertexai) is missing required fields")
          err.message should include(
            "- project: the GCP project ID that owns your Vertex AI resources " +
              "(set it in application.conf under llm4s.providers.vertex-missing.project)"
          )
        case Right(cfg) =>
          fail(s"Expected a missing-project failure, got config: $cfg")
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
          gemini.apiKey.reveal shouldBe "google-key"
          gemini.baseUrl shouldBe GeminiConfig.DEFAULT_BASE_URL
        case other =>
          fail(s"Expected GeminiConfig, got $other")
    }

    "read googleSearch from gemini and vertexai sections, off unless set (#1216)" in {
      def load(name: String, body: String) =
        Llm4sConfig.provider(ConfigSource.string(s"llm4s.providers.$name { $body }"), name)
      val gemini = """provider = "gemini", model = "gemini-2.5-flash", apiKey = "k""""
      val vertex = """provider = "vertexai", model = "gemini-2.5-flash", project = "p""""

      load("g-off", gemini) should matchPattern { case Right(c: GeminiConfig) if !c.googleSearch => }
      load("g-on", s"$gemini, googleSearch = true") should matchPattern {
        case Right(c: GeminiConfig) if c.googleSearch =>
      }
      load("g-str", s"""$gemini, googleSearch = "TRUE" """) should matchPattern {
        case Right(c: GeminiConfig) if c.googleSearch =>
      }
      load("v-off", vertex) should matchPattern { case Right(c: VertexAIConfig) if !c.googleSearch => }
      load("v-on", s"$vertex, googleSearch = true") should matchPattern {
        case Right(c: VertexAIConfig) if c.googleSearch =>
      }
    }

    "refuse a googleSearch value that is not true or false" in {
      val hocon = """llm4s.providers.g-bad { provider = "gemini", model = "m", apiKey = "k", googleSearch = "yes" }"""
      Llm4sConfig.provider(ConfigSource.string(hocon), "g-bad").left.map(_.message) match
        case Left(message) => message should include("googleSearch must be true or false, got 'yes'")
        case Right(cfg)    => fail(s"expected an error, got $cfg")
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
          |      project = "my-gcp-project"
          |      location = "europe-west4"
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

    "fail to load a Vertex AI section still written with the old endpoint/organization keys" in {
      val hocon =
        """
          |llm4s.providers.vertex-legacy {
          |  provider = "vertexai"
          |  model = "gemini-2.0-flash"
          |  endpoint = "legacy-project"
          |  organization = "asia-northeast1"
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "vertex-legacy") match
        case Left(err) => err.message should include("- project: the GCP project ID")
        case other     => fail(s"Expected a missing-project failure, got $other")
    }

    "default the Vertex AI region when a section sets no location" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    vertex-default {
          |      provider = "vertex"
          |      model = "gemini-2.0-flash"
          |      project = "my-gcp-project"
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
