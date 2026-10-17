package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.provider.OllamaProvider
import org.llm4s.llmconnect.spi.ProviderDescriptor
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

    "accept a section with no usable baseUrl, which then means the local server (#1991)" in {
      validate(
        "ollama-local",
        RawNamedProviderSection(
          provider = Some("ollama"),
          model = Some("llama3:latest"),
          baseUrl = Some("   "),
          apiKey = None,
        )
      ) match
        case Right(cfg) =>
          cfg.baseUrl shouldBe None
          ProviderDescriptor.resolveBaseUrl("ollama-local", cfg, OllamaProvider.configSpec) shouldBe
            Right("http://localhost:11434")
        case Left(err) =>
          fail(s"Expected the section to validate with the default baseUrl, got error: ${err.message}")
    }

    "keep a section's own baseUrl over the default" in {
      validate(
        "ollama-remote",
        RawNamedProviderSection(
          provider = Some("ollama"),
          model = Some("llama3:latest"),
          baseUrl = Some("http://gpu-box:11434"),
          apiKey = None,
        )
      ).flatMap(ProviderDescriptor.resolveBaseUrl("ollama-remote", _, OllamaProvider.configSpec)) shouldBe
        Right("http://gpu-box:11434")
    }
  }

  "NamedProviderSectionValidator" should {
    "report no missing field for an Ollama section without baseUrl" in {
      val name    = ProviderName("my-ollama")
      val section = RawNamedProviderSection(Some("ollama"), Some("llama3"), None, None)
      val result = NamedProviderConfigNormalizer
        .normalize(name, section)
        .flatMap(NamedProviderSectionValidator.validate(name, OllamaProvider, _))

      result.map(_.model.asString) shouldBe Right("llama3")
    }

    "declare OLLAMA_BASE_URL as the conventional variable, and default to localhost" in {
      OllamaProvider.configSpec.defaultBaseUrl shouldBe Some("http://localhost:11434")
      OllamaProvider.configSpec.baseUrlEnv shouldBe Some(OllamaConfigKeys.OLLAMA_BASE_URL)
      OllamaProvider.configSpec.requiresBaseUrl shouldBe false
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

    "list models from the local server for a section without baseUrl (#1991)" in {
      val hocon =
        """
          |llm4s.providers {
          |  provider = "ollama-main"
          |  ollama-main { provider = "ollama", model = "llama3.1" }
          |}
          |""".stripMargin

      val httpClient = new MockHttpClient(HttpResponse(200, """{"models": [{"name": "llama3.1:latest"}]}""", Map.empty))

      Llm4sConfig.listModels("ollama-main", ConfigSource.string(hocon), httpClient).map(_.map(_.name.asString)) shouldBe
        Right(List("llama3.1:latest"))
      httpClient.lastUrl shouldBe Some("http://localhost:11434/api/tags")
    }
  }
