package org.llm4s.spring

import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig, OpenAIConfig }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProviderConfigParserSpec extends AnyFlatSpec with Matchers {

  private def props(
    provider: String = "",
    model: String = "",
    apiKey: String = "",
    baseUrl: String = "",
    organization: String = "",
    contextWindow: Int = 128000,
    reserveCompletion: Int = 4096
  ): Llm4sProperties = {
    val p = new Llm4sProperties
    p.provider = provider
    p.model = model
    p.apiKey = apiKey
    p.baseUrl = baseUrl
    p.organization = organization
    p.contextWindow = contextWindow
    p.reserveCompletion = reserveCompletion
    p
  }

  "ProviderConfigParser.parse" should "fail when provider is empty" in {
    val result = ProviderConfigParser.parse(props())
    result.isFailure shouldBe true
    result.getError().getMessage should include("llm4s.provider is required")
  }

  it should "fail when model is empty" in {
    val result = ProviderConfigParser.parse(props(provider = "openai"))
    result.isFailure shouldBe true
    result.getError().getMessage should include("llm4s.model is required")
  }

  it should "fail for an unknown provider" in {
    val result = ProviderConfigParser.parse(props(provider = "unknown-llm", model = "m"))
    result.isFailure shouldBe true
    result.getError().getMessage should include("Unknown provider")
  }

  "OpenAI parsing" should "succeed with all required fields" in {
    val result = ProviderConfigParser.parse(
      props(provider = "openai", model = "gpt-4o", apiKey = "sk-test")
    )
    result.isSuccess shouldBe true
    val config = result.get().asInstanceOf[OpenAIConfig]
    config.model shouldBe "gpt-4o"
    config.apiKey shouldBe "sk-test"
    config.baseUrl shouldBe "https://api.openai.com/v1"
    config.organization shouldBe None
  }

  it should "fail when apiKey is empty" in {
    val result = ProviderConfigParser.parse(props(provider = "openai", model = "gpt-4o"))
    result.isFailure shouldBe true
    result.getError().getMessage should include("api-key")
  }

  it should "use a custom baseUrl when provided" in {
    val result = ProviderConfigParser.parse(
      props(provider = "openai", model = "m", apiKey = "k", baseUrl = "https://custom.api/v1")
    )
    result.get().asInstanceOf[OpenAIConfig].baseUrl shouldBe "https://custom.api/v1"
  }

  it should "include organization when provided" in {
    val result = ProviderConfigParser.parse(
      props(provider = "openai", model = "m", apiKey = "k", organization = "org-123")
    )
    result.get().asInstanceOf[OpenAIConfig].organization shouldBe Some("org-123")
  }

  it should "be case-insensitive for provider name" in {
    val result = ProviderConfigParser.parse(
      props(provider = "OpenAI", model = "gpt-4o", apiKey = "sk-test")
    )
    result.isSuccess shouldBe true
  }

  "Anthropic parsing" should "succeed with all required fields" in {
    val result = ProviderConfigParser.parse(
      props(provider = "anthropic", model = "claude-sonnet-4-5-latest", apiKey = "sk-ant-test")
    )
    result.isSuccess shouldBe true
    val config = result.get().asInstanceOf[AnthropicConfig]
    config.model shouldBe "claude-sonnet-4-5-latest"
    config.apiKey shouldBe "sk-ant-test"
    config.baseUrl shouldBe "https://api.anthropic.com"
  }

  it should "fail when apiKey is empty" in {
    val result = ProviderConfigParser.parse(props(provider = "anthropic", model = "claude"))
    result.isFailure shouldBe true
  }

  it should "use a custom baseUrl when provided" in {
    val result = ProviderConfigParser.parse(
      props(provider = "anthropic", model = "m", apiKey = "k", baseUrl = "https://proxy.example.com")
    )
    result.get().asInstanceOf[AnthropicConfig].baseUrl shouldBe "https://proxy.example.com"
  }

  "Ollama parsing" should "succeed without an apiKey" in {
    val result = ProviderConfigParser.parse(
      props(provider = "ollama", model = "llama3")
    )
    result.isSuccess shouldBe true
    val config = result.get().asInstanceOf[OllamaConfig]
    config.model shouldBe "llama3"
    config.baseUrl shouldBe "http://localhost:11434"
  }

  it should "use a custom baseUrl when provided" in {
    val result = ProviderConfigParser.parse(
      props(provider = "ollama", model = "llama3", baseUrl = "http://my-server:11434")
    )
    result.get().asInstanceOf[OllamaConfig].baseUrl shouldBe "http://my-server:11434"
  }

  it should "forward contextWindow and reserveCompletion" in {
    val result = ProviderConfigParser.parse(
      props(provider = "ollama", model = "m", contextWindow = 8192, reserveCompletion = 1024)
    )
    val config = result.get().asInstanceOf[OllamaConfig]
    config.contextWindow shouldBe 8192
    config.reserveCompletion shouldBe 1024
  }
}
