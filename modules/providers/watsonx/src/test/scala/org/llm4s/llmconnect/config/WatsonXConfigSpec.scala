package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.model.ModelRegistryTestSupport
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WatsonXConfigSpec extends AnyFunSuite with Matchers:
  private given ContextWindowResolver = ContextWindowResolver(ModelRegistryTestSupport.defaultService())

  private def build(
    apiKey: String = "key",
    project: Option[String] = Some("p-1"),
    space: Option[String] = None,
    baseUrl: String = WatsonXConfig.DEFAULT_BASE_URL
  ) = WatsonXConfig.fromValues("ibm/granite-13b-instruct-v2", apiKey, project, space, baseUrl)

  test("fromValues applies the Dallas endpoint, API version and IAM endpoint by default") {
    val config = build().getOrElse(fail("expected a config"))
    config.baseUrl shouldBe "https://us-south.ml.cloud.ibm.com"
    config.apiVersion shouldBe "2024-05-31"
    config.iamUrl shouldBe "https://iam.cloud.ibm.com/identity/token"
    config.providerId.asString shouldBe "watsonx"
    config.endpointUrl shouldBe Some(config.baseUrl)
    config.contextWindow should be > 0
    config.reserveCompletion should be > 0
  }

  test("a space id alone is enough, and blank ids count as absent") {
    build(project = None, space = Some("s-1")).map(_.spaceId) shouldBe Right(Some("s-1"))
    build(project = Some("  "), space = Some(" ")).left.toOption
      .exists(_.isInstanceOf[ConfigurationError]) shouldBe true
  }

  test("a blank api key or base url is a ConfigurationError, not an exception") {
    build(apiKey = " ").left.toOption.exists(_.isInstanceOf[ConfigurationError]) shouldBe true
    build(baseUrl = "").left.toOption.exists(_.isInstanceOf[ConfigurationError]) shouldBe true
  }

  test("toString redacts the api key") {
    val text = build(apiKey = "super-secret").getOrElse(fail("expected a config")).toString
    (text should not).include("super-secret")
    text should include("projectId=p-1")
  }

  test("withModel replaces only the model") {
    val config = build().getOrElse(fail("expected a config"))
    config.withModel("meta-llama/llama-3-8b-instruct").model shouldBe "meta-llama/llama-3-8b-instruct"
    config.withModel("x").projectId shouldBe config.projectId
  }
