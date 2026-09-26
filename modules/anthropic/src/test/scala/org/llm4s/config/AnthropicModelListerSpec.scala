package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Moved from core's `ProviderModelListerSpec` with the lister it tests (#1132). */
class AnthropicModelListerSpec extends AnyFunSuite with Matchers:

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

  test("Anthropic lister discovers models from /v1/models") {
    val config = namedConfig(ProviderId("anthropic"), "claude-sonnet-4-20250514", apiKey = Some("sk-ant-test"))
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
    val result = AnthropicModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001")
        models.map(_.provider) shouldBe List(ProviderId("anthropic"), ProviderId("anthropic"))
        mockHttp.lastUrl shouldBe Some("https://api.anthropic.com/v1/models")
        mockHttp.getRequests.map(_._3) shouldBe Seq(
          Map("limit" -> "100"),
          Map("limit" -> "100", "after_id" -> "claude-sonnet-4-20250514")
        )
      case Left(err) =>
        fail(s"Expected discovered Anthropic models, got error: ${err.message}")
  }

  test("Anthropic lister fails when has_more=true but last_id is missing") {
    val config = namedConfig(ProviderId("anthropic"), "claude-sonnet-4-20250514", apiKey = Some("sk-ant-test"))
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
    val result   = AnthropicModelLister.listModels(config, mockHttp)

    result match
      case Left(err) =>
        err.message should include("has_more=true without last_id")
      case Right(models) =>
        fail(s"Expected malformed Anthropic pagination failure, got models: $models")
  }

  test("Anthropic lister uses a configured baseUrl in place of the default") {
    val config = namedConfig(
      ProviderId("anthropic"),
      "claude-sonnet-4-20250514",
      baseUrl = Some("https://anthropic-proxy.example"),
      apiKey = Some("sk-ant-test")
    )
    val mockHttp = MockHttpClient(HttpResponse(200, """{ "data": [] }""", Map.empty))

    AnthropicModelLister.listModels(config, mockHttp) shouldBe Right(Nil)
    mockHttp.lastUrl shouldBe Some("https://anthropic-proxy.example/v1/models")
    mockHttp.lastHeaders.get should contain("anthropic-version" -> "2023-06-01")
    mockHttp.lastHeaders.get should contain("x-api-key" -> "sk-ant-test")
  }

  test("Anthropic lister skips entries without an id and rejects a payload without data") {
    val config = namedConfig(ProviderId("anthropic"), "claude-sonnet-4-20250514", apiKey = Some("sk-ant-test"))

    val skipped = AnthropicModelLister.listModels(
      config,
      MockHttpClient(HttpResponse(200, """{ "data": [ { "display_name": "nameless" } ] }""", Map.empty))
    )
    skipped shouldBe Right(Nil)

    AnthropicModelLister.listModels(config, MockHttpClient(HttpResponse(200, "{}", Map.empty))) match
      case Left(err)     => err.message should include("Missing or invalid models payload")
      case Right(models) => fail(s"Expected a payload error, got models: $models")
  }

  test("Anthropic lister rejects a has_more that is not a boolean") {
    val config = namedConfig(ProviderId("anthropic"), "claude-sonnet-4-20250514", apiKey = Some("sk-ant-test"))
    val body   = """{ "data": [], "has_more": "yes" }"""

    AnthropicModelLister.listModels(config, MockHttpClient(HttpResponse(200, body, Map.empty))) match
      case Left(err)     => err.message should include("Invalid boolean value for `has_more`")
      case Right(models) => fail(s"Expected a has_more error, got models: $models")
  }

  test("Anthropic lister fails clearly for unsupported providers") {
    val config = namedConfig(ProviderId("openai"), "gpt-4o-mini", apiKey = Some("test-key"))

    AnthropicModelLister.listModels(config, Llm4sHttpClient.create()) match
      case Left(err) =>
        err.message should include("Model discovery is not supported yet")
        err.message should include("openai")
      case Right(models) =>
        fail(s"Expected unsupported provider error, got models: $models")
  }

  test("the registered Anthropic provider exposes its model lister") {
    ProviderRegistry.default.get(ProviderId("anthropic")) match
      case Right(descriptor) => descriptor.modelLister shouldBe Some(AnthropicModelLister)
      case Left(err)         => fail(s"Expected the Anthropic provider to be registered, got error: ${err.message}")
  }
