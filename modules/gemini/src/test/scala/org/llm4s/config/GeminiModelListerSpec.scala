package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Moved from core's `ProviderModelListerSpec` with the lister it tests (#1132). */
class GeminiModelListerSpec extends AnyFunSuite with Matchers:

  private def namedConfig(
    providerId: ProviderId,
    model: String,
    baseUrl: Option[String] = None,
    apiKey: Option[String]
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

  test("Gemini lister discovers models from /models") {
    val config = namedConfig(ProviderId("gemini"), "gemini-3-flash-preview", apiKey = Some("google-key"))
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
    val result = GeminiModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("gemini-2.0-flash", "gemini-2.5-pro")
        models.map(_.provider) shouldBe List(ProviderId("gemini"), ProviderId("gemini"))
        models.head.metadata("inputTokenLimit") shouldBe "1048576"
        models.head.metadata("supportedGenerationMethods") shouldBe "generateContent,countTokens"
        mockHttp.lastUrl shouldBe Some("https://generativelanguage.googleapis.com/v1beta/models")
        mockHttp.getRequests.map(_._3) shouldBe Seq(
          Map("pageSize" -> "1000"),
          Map("pageSize" -> "1000", "pageToken" -> "page-2")
        )
      case Left(err) =>
        fail(s"Expected discovered Gemini models, got error: ${err.message}")
  }

  test("Gemini lister uses a configured baseUrl in place of the default") {
    val config = namedConfig(
      ProviderId("gemini"),
      "gemini-2.0-flash",
      baseUrl = Some("https://gemini-proxy.example/v1beta"),
      apiKey = Some("google-key")
    )
    val mockHttp = MockHttpClient(HttpResponse(200, """{ "models": [] }""", Map.empty))

    GeminiModelLister.listModels(config, mockHttp) shouldBe Right(Nil)
    mockHttp.lastUrl shouldBe Some("https://gemini-proxy.example/v1beta/models")
  }

  test("Gemini lister skips entries without a name and rejects a payload without models") {
    val config = namedConfig(ProviderId("gemini"), "gemini-2.0-flash", apiKey = Some("google-key"))

    val skipped = GeminiModelLister.listModels(
      config,
      MockHttpClient(HttpResponse(200, """{ "models": [ { "displayName": "nameless" } ] }""", Map.empty))
    )
    skipped shouldBe Right(Nil)

    GeminiModelLister.listModels(config, MockHttpClient(HttpResponse(200, "{}", Map.empty))) match
      case Left(err)     => err.message should include("Missing or invalid Gemini models payload")
      case Right(models) => fail(s"Expected a payload error, got models: $models")
  }

  test("Gemini lister fails clearly for unsupported providers") {
    val config = namedConfig(ProviderId("openai"), "gpt-4o-mini", apiKey = Some("test-key"))

    GeminiModelLister.listModels(config, Llm4sHttpClient.create()) match
      case Left(err) =>
        err.message should include("Model discovery is not supported yet")
        err.message should include("openai")
      case Right(models) =>
        fail(s"Expected unsupported provider error, got models: $models")
  }

  test("the registered Gemini provider exposes its model lister") {
    ProviderRegistry.default.get(ProviderId("gemini")) match
      case Right(descriptor) => descriptor.modelLister shouldBe Some(GeminiModelLister)
      case Left(err)         => fail(s"Expected the Gemini provider to be registered, got error: ${err.message}")
  }
