package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigReaderPrecedenceSpec extends AnyWordSpec with Matchers {

  "Llm4sConfig.defaultProvider" should {
    "read llm4s.providers values from test application.conf" in {
      val cfg = Llm4sConfig
        .defaultProvider()
        .fold(err => fail(err.toString), identity)

      cfg.model shouldBe "fixture-model"
    }

    "allow -D system property to override application.conf" in {
      val key      = "llm4s.providers.fixturechat-main.apiKey"
      val original = System.getProperty(key)
      try {
        System.setProperty(key, "overridden-key")
        // Ensure updated system properties are visible to ConfigSource.default.
        ConfigFactory.invalidateCaches()
        val cfg = Llm4sConfig
          .defaultProvider()
          .fold(err => fail(err.toString), identity)
        cfg match {
          case fixture: org.llm4s.testutil.FixtureChatConfig =>
            fixture.apiKey shouldBe "overridden-key"
          case other =>
            fail(s"Expected FixtureChatConfig, got $other")
        }
      } finally if (original == null) System.clearProperty(key) else System.setProperty(key, original)
    }
  }
}
