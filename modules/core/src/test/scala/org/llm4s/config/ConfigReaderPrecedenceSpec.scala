package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigReaderPrecedenceSpec extends AnyWordSpec with Matchers {

  "LLMConfig" should {
    "read llm4s.* values from test application.conf" in {
      val cfg = Llm4sConfig
        .provider()
        .fold(err => fail(err.toString), identity)

      cfg.model shouldBe "gpt-4o"
    }

    "allow -D system property to override application.conf" in {
      val key      = "llm4s.openai.apiKey"
      val original = System.getProperty(key)
      try {
        System.setProperty(key, "overridden-key")
        // Ensure updated system properties are visible to ConfigSource.default.
        ConfigFactory.invalidateCaches()
        val cfg = Llm4sConfig
          .provider()
          .fold(err => fail(err.toString), identity)
        cfg match {
          case openai: org.llm4s.llmconnect.config.OpenAIConfig =>
            openai.apiKey shouldBe "overridden-key"
          case other =>
            fail(s"Expected OpenAIConfig, got $other")
        }
      } finally
        if (original == null) System.clearProperty(key) else System.setProperty(key, original)
    }
  }
}
