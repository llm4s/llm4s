package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Moved from core's `ProviderModelListerSpec` with the lister it tests (#1132). */
class OllamaModelListerSpec extends AnyFunSuite with Matchers:

  private def namedConfig(
    providerId: ProviderId,
    model: String,
    baseUrl: Option[String],
    apiKey: Option[String] = None
  ): NamedProviderConfig =
    NamedProviderConfig(
      provider = providerId,
      model = ModelName(model),
      baseUrl = baseUrl.map(BaseUrl(_)),
      apiKey = apiKey.map(ApiKey(_)),
      organization = None,
      endpoint = None,
      apiVersion = None
    )

  test("Ollama lister discovers models from /api/tags") {
    val config = namedConfig(ProviderId("ollama"), "llama3.1", baseUrl = Some("http://localhost:11434"))

    val responseBody =
      """{
        |  "models": [
        |    {
        |      "name": "llama3.2:latest",
        |      "modified_at": "2026-03-27T08:00:00Z",
        |      "size": 2019393189,
        |      "digest": "sha256:abc123",
        |      "details": {
        |        "format": "gguf",
        |        "family": "llama",
        |        "parameter_size": "3B",
        |        "quantization_level": "Q4_K_M"
        |      }
        |    },
        |    {
        |      "name": "mistral:latest",
        |      "modified_at": "2026-03-27T08:05:00Z",
        |      "size": 4112345678,
        |      "digest": "sha256:def456"
        |    }
        |  ]
        |}""".stripMargin

    val mockHttp = MockHttpClient(HttpResponse(200, responseBody, Map.empty))

    val result = OllamaModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name) shouldBe List(ModelName("llama3.2:latest"), ModelName("mistral:latest"))
        models.forall(_.provider == ProviderId("ollama")) shouldBe true
        mockHttp.lastUrl shouldBe Some("http://localhost:11434/api/tags")

        val llama = models.head
        llama.metadata("modifiedAt") shouldBe "2026-03-27T08:00:00Z"
        llama.metadata("size") shouldBe "2019393189"
        llama.metadata("digest") shouldBe "sha256:abc123"
        llama.metadata("format") shouldBe "gguf"
        llama.metadata("family") shouldBe "llama"
        llama.metadata("parameterSize") shouldBe "3B"
        llama.metadata("quantizationLevel") shouldBe "Q4_K_M"
      case Left(err) =>
        fail(s"Expected discovered Ollama models, got error: ${err.message}")
  }

  test("Ollama lister fails clearly for unsupported providers") {
    val config = namedConfig(
      ProviderId("openai"),
      "gpt-4o-mini",
      baseUrl = Some("https://api.openai.com/v1"),
      apiKey = Some("test-key")
    )

    val result = OllamaModelLister.listModels(config, Llm4sHttpClient.create())

    result match
      case Left(err) =>
        err.message should include("Model discovery is not supported yet")
        err.message should include("openai")
      case Right(models) =>
        fail(s"Expected unsupported provider error, got models: $models")
  }

  test("the registered Ollama provider exposes its model lister") {
    val result = ProviderRegistry.default.get(ProviderId("ollama"))

    result match
      case Right(descriptor) =>
        descriptor.modelLister shouldBe Some(OllamaModelLister)
      case Left(err) =>
        fail(s"Expected the Ollama provider to be registered, got error: ${err.message}")
  }
