package org.llm4s.llmconnect.config

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.provider.WatsonXProvider
import org.llm4s.model.ModelRegistryTestSupport
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Negative and edge cases for config validation and the descriptor's section-to-config mapping. */
class WatsonXConfigValidationSpec extends AnyFunSuite with Matchers:
  private given ContextWindowResolver = ContextWindowResolver(ModelRegistryTestSupport.defaultService())

  private def build(
    apiKey: String = "key",
    project: Option[String] = Some("p"),
    space: Option[String] = None,
    baseUrl: String = WatsonXConfig.DEFAULT_BASE_URL,
    apiVersion: String = WatsonXConfig.DEFAULT_API_VERSION,
    iamUrl: String = WatsonXConfig.DEFAULT_IAM_URL
  ) = WatsonXConfig.fromValues("ibm/granite-13b-instruct-v2", apiKey, project, space, baseUrl, apiVersion, iamUrl)

  test(
    "each of apiKey, baseUrl, apiVersion and iamUrl, blank or whitespace, is a ConfigurationError naming the field"
  ) {
    Seq(
      "apiKey"     -> build(apiKey = ""),
      "apiKey"     -> build(apiKey = "   "),
      "baseUrl"    -> build(baseUrl = " "),
      "apiVersion" -> build(apiVersion = ""),
      "iamUrl"     -> build(iamUrl = "\t")
    ).foreach { case (field, result) =>
      result.left.toOption match
        case Some(error: ConfigurationError) => withClue(field)(error.message should include(field))
        case other                           => fail(s"$field: expected a ConfigurationError, got $other")
    }
  }

  test("neither a project nor a space is a ConfigurationError; blank ids count as absent") {
    Seq(build(project = None), build(project = Some(""), space = Some("")), build(project = Some(" "), space = None))
      .foreach(_.left.toOption.exists(_.isInstanceOf[ConfigurationError]) shouldBe true)
  }

  test("a project and a space together are accepted and the space wins (not an either-or error)") {
    val config = build(project = Some("p"), space = Some("s")).getOrElse(fail("expected a config"))
    config.spaceId shouldBe Some("s")
    config.projectId shouldBe "p"
  }

  test("ids are trimmed; the api key is kept verbatim (a trailing newline is not stripped)") {
    val config = build(apiKey = "key\n", project = Some(" p "), space = None).getOrElse(fail("expected a config"))
    config.projectId shouldBe "p"
    config.apiKey shouldBe "key\n"
  }

  test("the URL scheme is not validated: plain http and even a non-http scheme are accepted (design gap)") {
    build(baseUrl = "http://wx.example.com").isRight shouldBe true
    build(iamUrl = "http://iam.example.com/identity/token").isRight shouldBe true
    build(baseUrl = "ftp://wx.example.com").isRight shouldBe true
  }

  test("toString redacts the key for a space-based config too, and shows the space") {
    val text = build(apiKey = "sup3rs3cret", project = None, space = Some("s-1")).getOrElse(fail("config")).toString
    (text should not).include("sup3rs3cret")
    text should include("spaceId=Some(s-1)")
  }

  private def section(
    apiKey: Option[String] = Some("key"),
    extras: Map[String, String] = Map(WatsonXProvider.ProjectIdKey -> "p")
  ) =
    NamedProviderConfig(
      provider = WatsonXProvider.id,
      model = ModelName("ibm/granite-13b-instruct-v2"),
      baseUrl = None,
      apiKey = apiKey.map(ApiKey(_)),
      extras = extras
    )

  test("the descriptor maps spaceId, apiVersion and iamUrl extras and falls back to the defaults") {
    val full = WatsonXProvider
      .buildConfig(
        "wx",
        section(extras =
          Map(
            WatsonXProvider.SpaceIdKey    -> "s-9",
            WatsonXProvider.ApiVersionKey -> "2025-01-01",
            WatsonXProvider.IamUrlKey     -> "https://iam.test.cloud.ibm.com/identity/token"
          )
        )
      )
      .getOrElse(fail("expected config"))
    full match
      case c: WatsonXConfig =>
        c.spaceId shouldBe Some("s-9")
        c.apiVersion shouldBe "2025-01-01"
        c.iamUrl shouldBe "https://iam.test.cloud.ibm.com/identity/token"
        c.baseUrl shouldBe WatsonXConfig.DEFAULT_BASE_URL
      case other => fail(s"unexpected $other")
  }

  test("the descriptor refuses a section without an api key, and one without a project or space") {
    WatsonXProvider.buildConfig("wx", section(apiKey = None)).isLeft shouldBe true
    WatsonXProvider.buildConfig("wx", section(extras = Map.empty)).isLeft shouldBe true
  }

  test("the descriptor identity: id watsonx, requires an api key, WATSONX_API_KEY, https default base URL") {
    WatsonXProvider.id.asString shouldBe "watsonx"
    WatsonXProvider.configSpec.requiresApiKey shouldBe true
    WatsonXProvider.configSpec.apiKeyEnv shouldBe Seq("WATSONX_API_KEY")
    WatsonXProvider.configSpec.defaultBaseUrl.exists(_.startsWith("https://")) shouldBe true
  }
