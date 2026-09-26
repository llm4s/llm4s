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

  // The OpenRouter and DeepSeek listers moved to llm4s-openai-compatible as
  // `OpenRouterModelLister` and `DeepSeekModelLister`, and their tests
  // to that module's `OpenAICompatibleModelListerSpec` (#1132).

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
