package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, WatsonXConfig }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WatsonXConfigSpec extends AnyFlatSpec with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  "WatsonXConfig" should "have correct provider kind" in {
    val cfg = WatsonXConfig(
      apiKey = "key",
      projectId = "proj",
      spaceId = None,
      model = "ibm/granite-13b-instruct-v2",
      baseUrl = WatsonXConfig.DEFAULT_BASE_URL,
      apiVersion = WatsonXConfig.DEFAULT_API_VERSION,
      contextWindow = 8192,
      reserveCompletion = 4096
    )
    cfg.provider.name shouldBe "watsonx"
  }

  it should "have correct DEFAULT_BASE_URL" in {
    WatsonXConfig.DEFAULT_BASE_URL shouldBe "https://us-south.ml.cloud.ibm.com"
  }

  it should "have correct DEFAULT_API_VERSION" in {
    WatsonXConfig.DEFAULT_API_VERSION shouldBe "2024-05-31"
  }

  it should "redact apiKey in toString" in {
    val cfg = WatsonXConfig(
      apiKey = "super-secret-key-12345",
      projectId = "proj-abc",
      spaceId = None,
      model = "ibm/granite-13b-instruct-v2",
      baseUrl = WatsonXConfig.DEFAULT_BASE_URL,
      apiVersion = WatsonXConfig.DEFAULT_API_VERSION,
      contextWindow = 8192,
      reserveCompletion = 4096
    )
    val str = cfg.toString
    str should not include "super-secret-key-12345"
    str should include("WatsonXConfig")
    str should include("proj-abc")
  }

  it should "construct via fromValues with defaults" in {
    val cfg = WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "test-api-key",
      projectId = "my-project"
    )
    cfg.model shouldBe "ibm/granite-13b-instruct-v2"
    cfg.apiKey shouldBe "test-api-key"
    cfg.projectId shouldBe "my-project"
    cfg.spaceId shouldBe None
    cfg.baseUrl shouldBe WatsonXConfig.DEFAULT_BASE_URL
    cfg.apiVersion shouldBe WatsonXConfig.DEFAULT_API_VERSION
  }

  it should "construct via fromValues with explicit space ID" in {
    val cfg = WatsonXConfig.fromValues(
      modelName = "meta-llama/llama-3-8b-instruct",
      apiKey = "test-key",
      projectId = "project-id",
      spaceId = Some("space-123")
    )
    cfg.spaceId shouldBe Some("space-123")
    cfg.model shouldBe "meta-llama/llama-3-8b-instruct"
  }

  it should "construct via fromValues with custom baseUrl and apiVersion" in {
    val cfg = WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "key",
      projectId = "proj",
      baseUrl = "https://eu-de.ml.cloud.ibm.com",
      apiVersion = "2024-01-01"
    )
    cfg.baseUrl shouldBe "https://eu-de.ml.cloud.ibm.com"
    cfg.apiVersion shouldBe "2024-01-01"
  }

  it should "fail fromValues with empty apiKey" in {
    an[IllegalArgumentException] should be thrownBy WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "",
      projectId = "proj"
    )
  }

  it should "fail fromValues with blank apiKey" in {
    an[IllegalArgumentException] should be thrownBy WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "   ",
      projectId = "proj"
    )
  }

  it should "fail fromValues with empty projectId" in {
    an[IllegalArgumentException] should be thrownBy WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "key",
      projectId = ""
    )
  }

  it should "fail fromValues with empty baseUrl" in {
    an[IllegalArgumentException] should be thrownBy WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "key",
      projectId = "proj",
      baseUrl = ""
    )
  }

  it should "resolve context window for granite-13b model" in {
    val cfg = WatsonXConfig.fromValues(
      modelName = "ibm/granite-13b-instruct-v2",
      apiKey = "key",
      projectId = "proj"
    )
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  it should "resolve context window for mistral-large model" in {
    val cfg = WatsonXConfig.fromValues(
      modelName = "mistralai/mistral-large",
      apiKey = "key",
      projectId = "proj"
    )
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 4096
  }

  it should "resolve context window for unknown model with 8192 default" in {
    val cfg = WatsonXConfig.fromValues(
      modelName = "some-unknown-model-xyz",
      apiKey = "key",
      projectId = "proj"
    )
    cfg.contextWindow shouldBe 8192
  }
}
