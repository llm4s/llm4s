package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.provider.OllamaProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * A named `ollama` provider section, from validation to model listing.
 *
 * Moved from core's `NamedProviderConfigValidatorSpec`, `NamedProviderSectionValidatorSpec`
 * and `Llm4sConfigProviderSpec` with the provider (#1132). These resolve `ollama` through
 * `ProviderRegistry.default`, so they also prove this module's services entry is found.
 */
class OllamaNamedProviderSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

    "validate and normalize an Ollama named provider section" in {
      validate(
        "ollama-local",
        RawNamedProviderSection(
          provider = Some("ollama"),
          model = Some("llama3:latest"),
          baseUrl = Some("http://localhost:11434"),
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("ollama")
          cfg.model.asString shouldBe "llama3:latest"
          cfg.baseUrl.map(_.asUrl) shouldBe Some("http://localhost:11434")
          cfg.apiKey shouldBe None
        case Left(err) =>
          fail(s"Expected Ollama NamedProviderConfig, got error: ${err.message}")
    }

    "fail clearly when Ollama baseUrl is missing" in {
      validate(
        "ollama-local",
        RawNamedProviderSection(
          provider = Some("ollama"),
          model = Some("llama3:latest"),
          baseUrl = Some("   "),
          apiKey = None,
          organization = None,
          endpoint = None,
          apiVersion = None,
        )
      ) match
        case Left(err) =>
          err.message should include("- baseUrl: set OLLAMA_BASE_URL")
        case Right(cfg) =>
          fail(s"Expected missing Ollama baseUrl failure, got config: $cfg")
    }
  }

  "NamedProviderSectionValidator" should {
    "mention missing Ollama fields by name" in {
      val name    = ProviderName("my-ollama")
      val section = RawNamedProviderSection(Some("ollama"), Some("llama3"), None, None, None, None, None)
      val result = NamedProviderConfigNormalizer
        .normalize(name, section)
        .flatMap(NamedProviderSectionValidator.validate(name, OllamaProvider, _))
      val message =
        result.left.toOption.getOrElse(fail(s"Expected Left, got $result")).asInstanceOf[ConfigurationError].message

      message should include("Provider 'my-ollama' (provider = ollama) is missing required fields")
      message should include("- baseUrl: set OLLAMA_BASE_URL (e.g. http://localhost:11434)")
    }
  }

  "Llm4sConfig" should {

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
          models.map(_.provider) shouldBe List(ProviderId("ollama"))
          httpClient.lastUrl shouldBe Some("http://localhost:11434/api/tags")
        case Left(err) =>
          fail(s"Expected listed models, got error: ${err.message}")
    }
  }
