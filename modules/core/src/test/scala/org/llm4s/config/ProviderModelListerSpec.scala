package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderModelListerSpec extends AnyFunSuite with Matchers:

  private def namedConfig(
    providerKind: ProviderKind,
    model: String,
    baseUrl: Option[String] = None,
    apiKey: Option[String] = None
  ): NamedProviderConfig =
    NamedProviderConfig(
      provider = providerKind,
      model = ModelName(model),
      baseUrl = baseUrl.map(BaseUrl(_)),
      apiKey = apiKey.map(ApiKey(_)),
      organization = None,
      endpoint = None,
      apiVersion = None
    )

  test("Ollama lister discovers models from /api/tags") {
    val config = namedConfig(ProviderKind.Ollama, "llama3.1", baseUrl = Some("http://localhost:11434"))

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

    val result = ProviderModelListers.Ollama.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name) shouldBe List(ModelName("llama3.2:latest"), ModelName("mistral:latest"))
        models.forall(_.provider == LLMProvider.Ollama) shouldBe true
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
      ProviderKind.OpenAI,
      "gpt-4o-mini",
      baseUrl = Some("https://api.openai.com/v1"),
      apiKey = Some("test-key")
    )

    val result = ProviderModelListers.Ollama.listModels(config, Llm4sHttpClient.create())

    result match
      case Left(err) =>
        err.message should include("Model discovery is not supported yet")
        err.message should include("openai")
      case Right(models) =>
        fail(s"Expected unsupported provider error, got models: $models")
  }

  test("provider capabilities expose the Ollama model lister") {
    val result = ProviderCapabilitiesRegistry.forKind(ProviderKind.Ollama)

    result match
      case Right(capabilities) =>
        capabilities.modelLister shouldBe Some(ProviderModelListers.Ollama)
      case Left(err) =>
        fail(s"Expected Ollama capabilities, got error: ${err.message}")
  }

  test("OpenAI lister discovers models from /models") {
    val config = namedConfig(ProviderKind.OpenAI, "gpt-4o-mini", apiKey = Some("sk-test"))
    val responseBody =
      """{
        |  "data": [
        |    {
        |      "id": "gpt-4o-mini",
        |      "created": 1710000000,
        |      "owned_by": "openai"
        |    }
        |  ]
        |}""".stripMargin

    val mockHttp = MockHttpClient(HttpResponse(200, responseBody, Map.empty))
    val result   = ProviderModelListers.OpenAI.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("gpt-4o-mini")
        models.map(_.provider) shouldBe List(LLMProvider.OpenAI)
        mockHttp.lastUrl shouldBe Some("https://api.openai.com/v1/models")
      case Left(err) =>
        fail(s"Expected discovered OpenAI models, got error: ${err.message}")
  }

  test("Anthropic lister discovers models from /v1/models") {
    val config = namedConfig(ProviderKind.Anthropic, "claude-sonnet-4-20250514", apiKey = Some("sk-ant-test"))
    val firstResponseBody =
      """{
        |  "data": [
        |    {
        |      "id": "claude-sonnet-4-20250514",
        |      "display_name": "Claude Sonnet 4",
        |      "created_at": "2025-02-19T00:00:00Z",
        |      "type": "model"
        |    }
        |  ],
        |  "has_more": true,
        |  "last_id": "claude-sonnet-4-20250514"
        |}""".stripMargin

    val secondResponseBody =
      """{
        |  "data": [
        |    {
        |      "id": "claude-haiku-4-5-20251001",
        |      "display_name": "Claude Haiku 4.5",
        |      "created_at": "2025-10-01T00:00:00Z",
        |      "type": "model"
        |    }
        |  ],
        |  "has_more": false
        |}""".stripMargin

    val mockHttp = MockHttpClient(
      Seq(
        HttpResponse(200, firstResponseBody, Map.empty),
        HttpResponse(200, secondResponseBody, Map.empty)
      )
    )
    val result = ProviderModelListers.Anthropic.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001")
        models.map(_.provider) shouldBe List(LLMProvider.Anthropic, LLMProvider.Anthropic)
        mockHttp.lastUrl shouldBe Some("https://api.anthropic.com/v1/models")
        mockHttp.getRequests.map(_._3) shouldBe Seq(
          Map("limit" -> "100"),
          Map("limit" -> "100", "after_id" -> "claude-sonnet-4-20250514")
        )
      case Left(err) =>
        fail(s"Expected discovered Anthropic models, got error: ${err.message}")
  }

  test("Anthropic lister fails when has_more=true but last_id is missing") {
    val config = namedConfig(ProviderKind.Anthropic, "claude-sonnet-4-20250514", apiKey = Some("sk-ant-test"))
    val responseBody =
      """{
        |  "data": [
        |    {
        |      "id": "claude-sonnet-4-20250514",
        |      "display_name": "Claude Sonnet 4",
        |      "created_at": "2025-02-19T00:00:00Z",
        |      "type": "model"
        |    }
        |  ],
        |  "has_more": true
        |}""".stripMargin

    val mockHttp = MockHttpClient(HttpResponse(200, responseBody, Map.empty))
    val result   = ProviderModelListers.Anthropic.listModels(config, mockHttp)

    result match
      case Left(err) =>
        err.message should include("has_more=true without last_id")
      case Right(models) =>
        fail(s"Expected malformed Anthropic pagination failure, got models: $models")
  }

  test("Gemini lister discovers models from /models") {
    val config = namedConfig(ProviderKind.Gemini, "gemini-3-flash-preview", apiKey = Some("google-key"))
    val firstResponseBody =
      """{
        |  "models": [
        |    {
        |      "name": "models/gemini-2.0-flash",
        |      "displayName": "Gemini 2.0 Flash",
        |      "description": "Fast model",
        |      "inputTokenLimit": 1048576,
        |      "outputTokenLimit": 8192,
        |      "supportedGenerationMethods": ["generateContent", "countTokens"]
        |    }
        |  ],
        |  "nextPageToken": "page-2"
        |}""".stripMargin

    val secondResponseBody =
      """{
        |  "models": [
        |    {
        |      "name": "models/gemini-2.5-pro",
        |      "displayName": "Gemini 2.5 Pro",
        |      "description": "Reasoning model"
        |    }
        |  ]
        |}""".stripMargin

    val mockHttp = MockHttpClient(
      Seq(
        HttpResponse(200, firstResponseBody, Map.empty),
        HttpResponse(200, secondResponseBody, Map.empty)
      )
    )
    val result = ProviderModelListers.Gemini.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("gemini-2.0-flash", "gemini-2.5-pro")
        models.map(_.provider) shouldBe List(LLMProvider.Gemini, LLMProvider.Gemini)
        mockHttp.lastUrl shouldBe Some("https://generativelanguage.googleapis.com/v1beta/models")
        mockHttp.getRequests.map(_._3) shouldBe Seq(
          Map("pageSize" -> "1000"),
          Map("pageSize" -> "1000", "pageToken" -> "page-2")
        )
      case Left(err) =>
        fail(s"Expected discovered Gemini models, got error: ${err.message}")
  }

  test("OpenRouter lister includes required OpenRouter headers") {
    val config = namedConfig(ProviderKind.OpenRouter, "openai/gpt-4o-mini", apiKey = Some("or-key"))
    val responseBody =
      """{
        |  "data": [
        |    {
        |      "id": "openai/gpt-4o-mini",
        |      "created": 1710000000,
        |      "owned_by": "openrouter"
        |    }
        |  ]
        |}""".stripMargin

    val mockHttp = MockHttpClient(HttpResponse(200, responseBody, Map.empty))
    val result   = ProviderModelListers.OpenRouter.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("openai/gpt-4o-mini")
        models.map(_.provider) shouldBe List(LLMProvider.OpenRouter)
        mockHttp.lastUrl shouldBe Some("https://openrouter.ai/api/v1/models")
        mockHttp.lastHeaders shouldBe defined
        mockHttp.lastHeaders.get should contain("HTTP-Referer" -> "https://github.com/llm4s/llm4s")
        mockHttp.lastHeaders.get should contain("X-Title" -> "LLM4S")
      case Left(err) =>
        fail(s"Expected discovered OpenRouter models, got error: ${err.message}")
  }

  test("DeepSeek lister discovers models from /models") {
    val config = namedConfig(ProviderKind.DeepSeek, "deepseek-chat", apiKey = Some("ds-key"))
    val responseBody =
      """{
        |  "data": [
        |    {
        |      "id": "deepseek-chat",
        |      "created": 1710000000,
        |      "owned_by": "deepseek"
        |    }
        |  ]
        |}""".stripMargin

    val mockHttp = MockHttpClient(HttpResponse(200, responseBody, Map.empty))
    val result   = ProviderModelListers.DeepSeek.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("deepseek-chat")
        models.map(_.provider) shouldBe List(LLMProvider.DeepSeek)
        mockHttp.lastUrl shouldBe Some("https://api.deepseek.com/models")
      case Left(err) =>
        fail(s"Expected discovered DeepSeek models, got error: ${err.message}")
  }

  test("Mistral lister discovers models from /v1/models") {
    val config = namedConfig(ProviderKind.Mistral, "mistral-large-latest", apiKey = Some("mistral-key"))
    val responseBody =
      """{
        |  "data": [
        |    {
        |      "id": "mistral-large-latest",
        |      "created": 1710000000,
        |      "owned_by": "mistral"
        |    }
        |  ]
        |}""".stripMargin

    val mockHttp = MockHttpClient(HttpResponse(200, responseBody, Map.empty))
    val result   = ProviderModelListers.Mistral.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("mistral-large-latest")
        models.map(_.provider) shouldBe List(LLMProvider.Mistral)
        mockHttp.lastUrl shouldBe Some("https://api.mistral.ai/v1/models")
      case Left(err) =>
        fail(s"Expected discovered Mistral models, got error: ${err.message}")
  }
