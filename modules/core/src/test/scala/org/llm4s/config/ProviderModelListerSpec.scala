package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderModelListerSpec extends AnyFunSuite with Matchers:

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

  test("OpenAI lister discovers models from /models") {
    val config = namedConfig(ProviderId("openai"), "gpt-4o-mini", apiKey = Some("sk-test"))
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
        models.map(_.provider) shouldBe List(ProviderId("openai"))
        mockHttp.lastUrl shouldBe Some("https://api.openai.com/v1/models")
      case Left(err) =>
        fail(s"Expected discovered OpenAI models, got error: ${err.message}")
  }

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
    val result   = ProviderModelListers.OpenRouter.listModels(config, mockHttp)

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
    val result   = ProviderModelListers.DeepSeek.listModels(config, mockHttp)

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
    val result   = ProviderModelListers.Mistral.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("mistral-large-latest")
        models.map(_.provider) shouldBe List(ProviderId("mistral"))
        mockHttp.lastUrl shouldBe Some("https://api.mistral.ai/v1/models")
      case Left(err) =>
        fail(s"Expected discovered Mistral models, got error: ${err.message}")
  }
