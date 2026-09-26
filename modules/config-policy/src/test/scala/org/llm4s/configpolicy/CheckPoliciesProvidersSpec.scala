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

    "resolve a vertexai config" in {
      providerIdFor("""provider = "vertexai", model = "gemini-2.0-flash", endpoint = "my-project"""") shouldBe
        Right(ProviderId("vertexai"))
    }
  }
}
