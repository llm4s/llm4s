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

  test("a project and a space together are a ConfigurationError naming both fields (the space no longer wins)") {
    build(project = Some("p"), space = Some("s")).left.toOption match
      case Some(e: ConfigurationError) =>
        e.message should include("not both")
        (e.missingKeys should contain).allOf("projectId", "spaceId")
      case other => fail(s"expected a ConfigurationError, got $other")
    // blank ids count as absent, so a blank project next to a space is still fine
    build(project = Some(" "), space = Some("s")).map(_.spaceId) shouldBe Right(Some("s"))
  }

  test("ids and the api key are trimmed (a trailing newline from an env file must not reach IAM)") {
    val config = build(apiKey = "key\n", project = Some(" p "), space = None).getOrElse(fail("expected a config"))
    config.projectId shouldBe "p"
    config.apiKey shouldBe "key"
    build(apiKey = "  k2 \r\n").map(_.apiKey) shouldBe Right("k2")
  }

  test("baseUrl and iamUrl: https is accepted; http only for localhost, 127.0.0.1 and ::1; the rest is refused") {
    val accepted = Seq(
      "https://wx.example.com",
      "HTTPS://wx.example.com/",
      "https://wx.example.com:8443/path",
      "http://localhost",
      "http://localhost:8080",
      "http://127.0.0.1:9999",
      "http://[::1]",
      "http://[::1]:8080/x"
    )
    val refused = Seq(
      "http://example.com",
      "http://localhost.evil.com",
      "http://127.0.0.1.evil.com",
      "http://evil.com/localhost",
      "http://user@evil.com:80@localhost",
      "ftp://wx.example.com",
      "wx.example.com",
      "//wx.example.com",
      "https://",
      "https:",
      "file:///etc/passwd",
      "not a url",
      "",
      "   "
    )
    accepted.foreach { url =>
      withClue(s"baseUrl $url: ")(build(baseUrl = url).isRight shouldBe true)
      withClue(s"iamUrl $url: ")(build(iamUrl = url).isRight shouldBe true)
    }
    refused.foreach { url =>
      withClue(s"baseUrl '$url': ")(build(baseUrl = url).left.toOption match
        case Some(e: ConfigurationError) => e.message should include("baseUrl")
        case other                       => fail(s"expected a ConfigurationError, got $other")
      )
      withClue(s"iamUrl '$url': ")(build(iamUrl = url).left.toOption match
        case Some(e: ConfigurationError) => e.message should include("iamUrl")
        case other                       => fail(s"expected a ConfigurationError, got $other")
      )
    }
  }

  test("config errors never contain the API key") {
    val key = "SUPERSECRETKEY123"
    Seq(
      build(apiKey = key, baseUrl = "http://example.com"),
      build(apiKey = key, iamUrl = "ftp://x"),
      build(apiKey = key, project = Some("p"), space = Some("s")),
      build(apiKey = key, project = None, space = None)
    ).foreach { r =>
      r.isLeft shouldBe true
      (r.left.toOption.map(e => e.message + e.toString).getOrElse("") should not).include(key)
    }
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
