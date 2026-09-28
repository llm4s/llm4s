package org.llm4s.llmconnect.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.LangfuseConfigLoader
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `LangfuseConfig` as `llm4s-observability` reads it. This was core's `TracingConfigSpec`
 * (which read `TracingSettings.langfuse`) and the Langfuse half of `ConfigRedactionSpec`,
 * until the config left core with its backend (#1133).
 */
class LangfuseConfigSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally {
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
  }

  private def isLangfuseConfigured: Boolean =
    Option(System.getenv("LANGFUSE_PUBLIC_KEY")).exists(_.nonEmpty) ||
      Option(System.getenv("LANGFUSE_SECRET_KEY")).exists(_.nonEmpty)

  "LangfuseConfigLoader.default" should {
    "provide defaults when not configured" in {
      // Skip test if Langfuse is configured in environment
      if (isLangfuseConfigured) {
        cancel("Test skipped: Langfuse is configured in environment (LANGFUSE_PUBLIC_KEY or LANGFUSE_SECRET_KEY)")
      }

      val cfg = LangfuseConfigLoader.default().fold(err => fail(err.toString), identity)
      cfg.url shouldBe LangfuseConfig.DEFAULT_URL
      cfg.env shouldBe LangfuseConfig.DEFAULT_ENV
      cfg.release shouldBe LangfuseConfig.DEFAULT_RELEASE
      cfg.version shouldBe LangfuseConfig.DEFAULT_VERSION
      cfg.publicKey shouldBe None
      cfg.secretKey shouldBe None
    }

    "load overridden values from -D llm4s.tracing.langfuse.*" in {
      val props = Map(
        "llm4s.tracing.langfuse.url"       -> "https://example.com/api",
        "llm4s.tracing.langfuse.publicKey" -> "pub",
        "llm4s.tracing.langfuse.secretKey" -> "sec",
        "llm4s.tracing.langfuse.env"       -> "staging",
        "llm4s.tracing.langfuse.release"   -> "2.0.0",
        "llm4s.tracing.langfuse.version"   -> "2.1.3"
      )
      withProps(props) {
        val cfg = LangfuseConfigLoader.default().fold(err => fail(err.toString), identity)
        cfg.url shouldBe "https://example.com/api"
        cfg.publicKey shouldBe Some("pub")
        cfg.secretKey shouldBe Some("sec")
        cfg.env shouldBe "staging"
        cfg.release shouldBe "2.0.0"
        cfg.version shouldBe "2.1.3"
      }
    }
  }

  "LangfuseConfig.fromExtras" should {
    "read every field of the llm4s.tracing.langfuse block" in {
      LangfuseConfig.fromExtras(
        Map(
          "url"       -> "https://custom.langfuse.com/api/public/ingestion",
          "publicKey" -> "pk-test-123",
          "secretKey" -> "sk-test-456",
          "env"       -> "staging",
          "release"   -> "2.0.0",
          "version"   -> "2.1.0"
        )
      ) shouldBe LangfuseConfig(
        "https://custom.langfuse.com/api/public/ingestion",
        Some("pk-test-123"),
        Some("sk-test-456"),
        "staging",
        "2.0.0",
        "2.1.0"
      )
    }

    "use the defaults for an empty block" in {
      LangfuseConfig.fromExtras(Map.empty) shouldBe LangfuseConfig()
      LangfuseConfig().url shouldBe "https://cloud.langfuse.com/api/public/ingestion"
    }

    "trim values and treat a blank one as unset" in {
      val cfg = LangfuseConfig.fromExtras(
        Map("publicKey" -> "  pk-with-spaces  ", "secretKey" -> "", "env" -> "  development  ", "url" -> "   ")
      )
      cfg.publicKey shouldBe Some("pk-with-spaces")
      cfg.secretKey shouldBe None
      cfg.env shouldBe "development"
      cfg.url shouldBe LangfuseConfig.DEFAULT_URL
    }
  }

  "LangfuseConfig toString" should {

    val secret = "SECRET_TEST_VALUE_12345"

    "not leak keys" in {
      val cfg = LangfuseConfig(
        url = "https://example.invalid",
        publicKey = Some(secret),
        secretKey = Some(secret),
        env = "dev",
        release = "local",
        version = "0"
      )

      (cfg.toString should not).include(secret)
      cfg.toString should include("Some(***)")
    }

    "render missing keys as None" in {
      val cfg = LangfuseConfig(
        url = "https://example.invalid",
        publicKey = None,
        secretKey = None,
        env = "dev",
        release = "local",
        version = "0"
      )

      cfg.toString should include("publicKey=None")
      cfg.toString should include("secretKey=None")
    }
  }
}
