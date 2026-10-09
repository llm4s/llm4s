package org.llm4s.configpolicy

import org.llm4s.config.Llm4sConfig
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * `CheckPolicies` resolves the provider a config names through the registry, so
 * it can only check configs for providers whose modules are on its classpath.
 *
 * CI's smoke run exercises one provider; this covers every carved provider
 * module (#1132), so a carve that forgets to add itself to `configPolicy`'s
 * dependencies fails here rather than in a user's policy check.
 */
class CheckPoliciesProvidersSpec extends AnyWordSpec with Matchers {

  private def providerIdFor(section: String): Either[String, ProviderId] =
    Llm4sConfig
      .providerFrom(ConfigSource.string(s"llm4s { providers { provider = \"main\"\n main { $section } } }"))
      .map(_.providerId)
      .left
      .map(_.formatted)

  "the config-policy CLI's classpath" should {
    "resolve an ollama config" in {
      providerIdFor("""provider = "ollama", model = "llama3", baseUrl = "http://localhost:11434"""") shouldBe
        Right(ProviderId("ollama"))
    }

    "resolve a gemini config" in {
      providerIdFor("""provider = "gemini", model = "gemini-2.0-flash", apiKey = "test-key"""") shouldBe
        Right(ProviderId("gemini"))
    }

    "resolve an anthropic config" in {
      providerIdFor("""provider = "anthropic", model = "claude-sonnet-4-5", apiKey = "test-key"""") shouldBe
        Right(ProviderId("anthropic"))
    }

    "resolve an openai config" in {
      providerIdFor("""provider = "openai", model = "gpt-4o-mini", apiKey = "test-key"""") shouldBe
        Right(ProviderId("openai"))
    }

    "resolve an azure config" in {
      providerIdFor(
        """provider = "azure", model = "gpt-4o", apiKey = "test-key", endpoint = "https://x.openai.azure.com""""
      ) shouldBe Right(ProviderId("azure"))
    }

    "resolve a requesty config" in {
      // Requesty builds an OpenAIConfig, which used to infer its providerId from the base URL
      // and so report `openai`; the descriptor now sets it (#1132).
      providerIdFor("""provider = "requesty", model = "openai/gpt-4o-mini", apiKey = "test-key"""") shouldBe
        Right(ProviderId("requesty"))
    }

    "resolve a deepseek config" in {
      providerIdFor("""provider = "deepseek", model = "deepseek-chat", apiKey = "test-key"""") shouldBe
        Right(ProviderId("deepseek"))
    }

    "resolve a mistral config" in {
      providerIdFor("""provider = "mistral", model = "mistral-small-latest", apiKey = "test-key"""") shouldBe
        Right(ProviderId("mistral"))
    }

    "resolve a cohere config" in {
      providerIdFor("""provider = "cohere", model = "command-a-03-2025", apiKey = "test-key"""") shouldBe
        Right(ProviderId("cohere"))
    }

    "resolve an openai-compatible config, which needs no API key" in {
      providerIdFor("""provider = "openai-compatible", model = "m", baseUrl = "http://localhost:8000/v1"""") shouldBe
        Right(ProviderId("openai-compatible"))
    }

    "resolve a bedrock config, which needs a region and no API key" in {
      providerIdFor(
        """provider = "bedrock", model = "anthropic.claude-3-5-sonnet-20241022-v2:0", region = "us-east-1""""
      ) shouldBe
        Right(ProviderId("bedrock"))
    }

    "resolve a vertexai config" in {
      providerIdFor("""provider = "vertexai", model = "gemini-2.0-flash", project = "my-project"""") shouldBe
        Right(ProviderId("vertexai"))
    }
  }
}
