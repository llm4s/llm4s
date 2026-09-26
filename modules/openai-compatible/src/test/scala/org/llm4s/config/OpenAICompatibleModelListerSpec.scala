package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The model listers `llm4s-openai-compatible` ships. The DeepSeek case is core's
 * `ProviderModelListerSpec` case, moved here with the lister (#1132).
 */
class OpenAICompatibleModelListerSpec extends AnyFunSuite with Matchers:

  private def namedConfig(
    providerId: ProviderId,
    model: String,
    baseUrl: Option[String] = None,
    apiKey: Option[String],
    headers: Map[String, String] = Map.empty
  ): NamedProviderConfig =
    NamedProviderConfig(
      provider = providerId,
      model = ModelName(model),
      baseUrl = baseUrl.map(BaseUrl(_)),
      apiKey = apiKey.map(ApiKey(_)),
      organization = None,
      endpoint = None,
      apiVersion = None,
      headers = headers
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
