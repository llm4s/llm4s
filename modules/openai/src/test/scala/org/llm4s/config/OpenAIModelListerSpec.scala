package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `OpenAIModelLister` and `RequestyModelLister`, which were `ProviderModelListers.OpenAI`
 * and `.Requesty` in core. The OpenAI case moved from core's `ProviderModelListerSpec`
 * with the provider (#1132).
 */
class OpenAIModelListerSpec extends AnyFunSuite with Matchers:

  private def namedConfig(
    providerId: ProviderId,
    model: String,
    baseUrl: Option[String] = None,
    apiKey: Option[String],
    organization: Option[String] = None
  ): NamedProviderConfig =
    NamedProviderConfig(
      provider = providerId,
      model = ModelName(model),
      baseUrl = baseUrl.map(BaseUrl(_)),
      apiKey = apiKey.map(ApiKey(_)),
      extras = organization.map(OpenAIConfig.OrganizationKey -> _).toMap
    )

  private val oneModel =
    """{
      |  "data": [
      |    {
      |      "id": "gpt-4o-mini",
      |      "created": 1710000000,
      |      "owned_by": "openai"
      |    }
      |  ]
      |}""".stripMargin

  test("OpenAI lister discovers models from /models") {
    val config   = namedConfig(ProviderId("openai"), "gpt-4o-mini", apiKey = Some("sk-test"))
    val mockHttp = MockHttpClient(HttpResponse(200, oneModel, Map.empty))
    val result   = OpenAIModelLister.listModels(config, mockHttp)

    result match
      case Right(models) =>
        models.map(_.name.asString) shouldBe List("gpt-4o-mini")
        models.map(_.provider) shouldBe List(ProviderId("openai"))
        mockHttp.lastUrl shouldBe Some("https://api.openai.com/v1/models")
      case Left(err) =>
        fail(s"Expected discovered OpenAI models, got error: ${err.message}")
  }

  test("OpenAI lister forwards the organisation header") {
    val config =
      namedConfig(ProviderId("openai"), "gpt-4o-mini", apiKey = Some("sk-test"), organization = Some("org-1"))
    val mockHttp = MockHttpClient(HttpResponse(200, oneModel, Map.empty))

    OpenAIModelLister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastHeaders.get should contain("OpenAI-Organization" -> "org-1")
    mockHttp.lastHeaders.get should contain("Authorization" -> "Bearer sk-test")
  }

  test("OpenAI lister sends no organisation header when the section sets none") {
    val config   = namedConfig(ProviderId("openai"), "gpt-4o-mini", apiKey = Some("sk-test"))
    val mockHttp = MockHttpClient(HttpResponse(200, oneModel, Map.empty))

    OpenAIModelLister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastHeaders.get.keySet should not contain "OpenAI-Organization"
  }

  test("Requesty lister forwards the organisation header") {
    val config =
      namedConfig(ProviderId("requesty"), "openai/gpt-4o-mini", apiKey = Some("rq-key"), organization = Some("org-2"))
    val mockHttp = MockHttpClient(HttpResponse(200, oneModel, Map.empty))

    RequestyModelLister.listModels(config, mockHttp).isRight shouldBe true
    mockHttp.lastHeaders.get should contain("OpenAI-Organization" -> "org-2")
  }

  test("Requesty lister discovers models from the Requesty router by default") {
    val config   = namedConfig(ProviderId("requesty"), "openai/gpt-4o-mini", apiKey = Some("rq-key"))
    val mockHttp = MockHttpClient(HttpResponse(200, oneModel, Map.empty))

    RequestyModelLister.listModels(config, mockHttp) match
      case Right(models) =>
        models.map(_.provider) shouldBe List(ProviderId("requesty"))
        mockHttp.lastUrl shouldBe Some("https://router.requesty.ai/v1/models")
      case Left(err) =>
        fail(s"Expected discovered Requesty models, got error: ${err.message}")
  }

  test("OpenAI lister refuses a section belonging to another provider") {
    val config   = namedConfig(ProviderId("requesty"), "gpt-4o-mini", apiKey = Some("sk-test"))
    val mockHttp = MockHttpClient(HttpResponse(200, oneModel, Map.empty))

    OpenAIModelLister.listModels(config, mockHttp).isLeft shouldBe true
  }
