package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The model listers `llm4s-openai-compatible` ships. The DeepSeek and Mistral cases are core's
 * `ProviderModelListerSpec` cases, moved here with the listers (#1132); the Mistral one is
 * the last, and the core spec went with it. The `ProviderModelListers.openAICompatible` cases
 * came with the factory itself, when it left core (#1133).
 */
class OpenAICompatibleModelListerSpec extends AnyFunSuite with Matchers:

  private def namedConfig(
    providerId: ProviderId,
    model: String,
    baseUrl: Option[String] = None,
    apiKey: Option[String],
    headers: Map[String, String] = Map.empty,
    extras: Map[String, String] = Map.empty
  ): NamedProviderConfig =
    NamedProviderConfig(
      provider = providerId,
      model = ModelName(model),
      baseUrl = baseUrl.map(BaseUrl(_)),
      apiKey = apiKey.map(ApiKey(_)),
      headers = headers,
      extras = extras
    )

  private val modelsBody = """{ "data": [ { "id": "some-model", "created": 1710000000, "owned_by": "x" } ] }"""

  test("OpenRouter lister includes required OpenRouter headers") {
    val config = namedConfig(ProviderId("openrouter"), "openai/gpt-4o-mini", apiKey = Some("or-key"))
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
    val result   = OpenRouterModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("openai/gpt-4o-mini")
        models.map(_.provider) shouldBe List(ProviderId("openrouter"))
        mockHttp.lastUrl shouldBe Some("https://openrouter.ai/api/v1/models")
        mockHttp.lastHeaders shouldBe defined
        mockHttp.lastHeaders.get should contain("HTTP-Referer" -> "https://github.com/llm4s/llm4s")
        mockHttp.lastHeaders.get should contain("X-Title" -> "LLM4S")
      case Left(err) =>
        fail(s"Expected discovered OpenRouter models, got error: ${err.message}")
  }

  test("DeepSeek lister discovers models from /models") {
    val config = namedConfig(ProviderId("deepseek"), "deepseek-chat", apiKey = Some("ds-key"))
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
    val result   = DeepSeekModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("deepseek-chat")
        models.map(_.provider) shouldBe List(ProviderId("deepseek"))
        mockHttp.lastUrl shouldBe Some("https://api.deepseek.com/models")
      case Left(err) =>
        fail(s"Expected discovered DeepSeek models, got error: ${err.message}")
  }

  test("Mistral lister discovers models from /v1/models") {
    val config = namedConfig(ProviderId("mistral"), "mistral-large-latest", apiKey = Some("mistral-key"))
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
    val result   = MistralModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("mistral-large-latest")
        models.map(_.provider) shouldBe List(ProviderId("mistral"))
        mockHttp.lastUrl shouldBe Some("https://api.mistral.ai/v1/models")
      case Left(err) =>
        fail(s"Expected discovered Mistral models, got error: ${err.message}")
  }

  // PR #1210 review: chat accepts a Mistral base URL that already ends in /v1
  // (MistralConfig.apiBaseUrl), and the lister must reach the same place rather than /v1/v1/models.
  test("Mistral lister lists <root>/v1/models for a configured base URL with or without /v1") {
    Seq(
      "https://mistral.example.test"     -> "https://mistral.example.test/v1/models",
      "https://mistral.example.test/"    -> "https://mistral.example.test/v1/models",
      "https://mistral.example.test/v1"  -> "https://mistral.example.test/v1/models",
      "https://mistral.example.test/v1/" -> "https://mistral.example.test/v1/models"
    ).foreach { (baseUrl, expected) =>
      withClue(baseUrl) {
        val config   = namedConfig(ProviderId("mistral"), "m", baseUrl = Some(baseUrl), apiKey = Some("k"))
        val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

        MistralModelLister.listModels(config, mockHttp).map(_.map(_.name.asString)) shouldBe Right(List("some-model"))
        mockHttp.lastUrl shouldBe Some(expected)
      }
    }
  }

  test("every lister in the module lists under the base its chat client posts to") {
    // Chat posts to <api base>/chat/completions; listing must use <api base>/models. DeepSeek,
    // OpenRouter and the generic provider use the configured base URL as the API base; Mistral
    // derives it with MistralConfig.apiBaseUrl. (Z.ai and Cohere have no lister.)
    val cases = Seq(
      (DeepSeekModelLister, ProviderId("deepseek"), "https://ds.example.test", "https://ds.example.test/models"),
      (
        OpenRouterModelLister,
        ProviderId("openrouter"),
        "https://or.example.test/api/v1",
        "https://or.example.test/api/v1/models"
      ),
      (
        OpenAICompatibleModelLister,
        ProviderId("openai-compatible"),
        "http://localhost:8000/v1/",
        "http://localhost:8000/v1/models"
      ),
      (MistralModelLister, ProviderId("mistral"), "https://api.mistral.ai/v1", "https://api.mistral.ai/v1/models")
    )
    cases.foreach { (lister, id, baseUrl, expected) =>
      withClue(id.asString) {
        val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))
        lister
          .listModels(namedConfig(id, "m", baseUrl = Some(baseUrl), apiKey = Some("k")), mockHttp)
          .isRight shouldBe true
        mockHttp.lastUrl shouldBe Some(expected)
      }
    }
  }

  test("openai-compatible lister sends no Authorization header without a key") {
    val config =
      namedConfig(ProviderId("openai-compatible"), "m", baseUrl = Some("http://localhost:1234/v1"), apiKey = None)
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    OpenAICompatibleModelLister.listModels(config, mockHttp).map(_.map(_.name.asString)) shouldBe Right(
      List("some-model")
    )
    mockHttp.lastUrl shouldBe Some("http://localhost:1234/v1/models")
    mockHttp.lastHeaders.get.keySet should not contain "Authorization"
  }

  test("openai-compatible lister sends the key and the section's headers") {
    val config = namedConfig(
      ProviderId("openai-compatible"),
      "m",
      baseUrl = Some("https://gateway.example/v1/"),
      apiKey = Some("gw-key"),
      headers = Map("X-Team" -> "search")
    )
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    OpenAICompatibleModelLister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastUrl shouldBe Some("https://gateway.example/v1/models")
    mockHttp.lastHeaders.get should contain("Authorization" -> "Bearer gw-key")
    mockHttp.lastHeaders.get should contain("X-Team" -> "search")
  }

  test("openai-compatible lister fails clearly without a base URL") {
    val config   = namedConfig(ProviderId("openai-compatible"), "m", apiKey = None)
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    OpenAICompatibleModelLister.listModels(config, mockHttp).left.map(_.message) shouldBe Left(
      "Configured provider is missing required field `baseUrl`"
    )
    mockHttp.lastUrl shouldBe None
  }

  test("OpenRouter lister forwards the section's organization as OpenAI-Organization") {
    val config = namedConfig(
      ProviderId("openrouter"),
      "openai/gpt-4o-mini",
      apiKey = Some("or-key"),
      extras = Map("organization" -> "org-1")
    )
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    OpenRouterModelLister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastHeaders.get should contain("OpenAI-Organization" -> "org-1")
  }

  test("a lister for a provider that does not declare organization never sends OpenAI-Organization") {
    // Validation would have dropped the key; even a section built in code with it is not read.
    val config = namedConfig(
      ProviderId("deepseek"),
      "deepseek-chat",
      apiKey = Some("ds-key"),
      extras = Map("organization" -> "org-1")
    )
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    DeepSeekModelLister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastHeaders.get.keySet should not contain "OpenAI-Organization"
  }

  test("openAICompatible layers the key, section-derived, provider and section headers in that order") {
    val lister = ProviderModelListers.openAICompatible(
      ProviderId("layered"),
      "https://layered.invalid/v1",
      extraHeaders = Map("X-Provider" -> "provider", "X-Shared" -> "provider"),
      sectionHeaders = section =>
        Map("X-From-Section" -> section.model.asString, "X-Shared" -> "section-derived", "Authorization" -> "derived")
    )
    val config = namedConfig(
      ProviderId("layered"),
      "m-1",
      apiKey = Some("k"),
      headers = Map("X-Provider" -> "user")
    )
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    lister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastHeaders.get shouldBe Map(
      "Authorization"  -> "derived",
      "X-From-Section" -> "m-1",
      "X-Shared"       -> "provider",
      "X-Provider"     -> "user"
    )
  }

  test("openAICompatible keeps each model's metadata and skips entries without an id") {
    val body =
      """{ "data": [
        |  { "id": "a", "created": 1710000000, "owned_by": "x", "name": "A", "description": "first" },
        |  { "id": "" },
        |  { "object": "model" },
        |  "not-an-object",
        |  { "id": "b" }
        |] }""".stripMargin
    val lister   = ProviderModelListers.openAICompatible(ProviderId("meta"), "https://meta.invalid")
    val mockHttp = MockHttpClient(HttpResponse(200, body, Map.empty))

    lister.listModels(namedConfig(ProviderId("meta"), "m", apiKey = Some("k")), mockHttp) shouldBe Right(
      List(
        DiscoveredModel(
          ModelName("a"),
          ProviderId("meta"),
          Map("created" -> "1710000000", "ownedBy" -> "x", "displayName" -> "A", "description" -> "first")
        ),
        DiscoveredModel(ModelName("b"), ProviderId("meta"))
      )
    )
  }

  test("openAICompatible fails clearly on a payload with no data array") {
    val lister   = ProviderModelListers.openAICompatible(ProviderId("meta"), "https://meta.invalid")
    val mockHttp = MockHttpClient(HttpResponse(200, """{ "models": [] }""", Map.empty))

    lister.listModels(namedConfig(ProviderId("meta"), "m", apiKey = Some("k")), mockHttp).left.map(_.message) match
      case Left(message) => message should include("Missing or invalid models payload")
      case Right(models) => fail(s"Expected a payload error, got $models")
  }

  test("openAICompatible requires an API key unless told otherwise") {
    val lister   = ProviderModelListers.openAICompatible(ProviderId("meta"), "https://meta.invalid")
    val mockHttp = MockHttpClient(HttpResponse(200, modelsBody, Map.empty))

    lister.listModels(namedConfig(ProviderId("meta"), "m", apiKey = None), mockHttp).isLeft shouldBe true
    mockHttp.lastUrl shouldBe None
  }

  test("openAICompatible reports a non-2xx listing response as an error") {
    val lister   = ProviderModelListers.openAICompatible(ProviderId("meta"), "https://meta.invalid")
    val mockHttp = MockHttpClient(HttpResponse(401, """{"error":"nope"}""", Map.empty))

    lister.listModels(namedConfig(ProviderId("meta"), "m", apiKey = Some("k")), mockHttp).isLeft shouldBe true
  }
