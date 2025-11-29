package org.llm4s.llmconnect.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.{ DefaultConfig, Llm4sConfig }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TracingConfigSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  private def isLangfuseConfigured: Boolean =
    Option(System.getenv("LANGFUSE_PUBLIC_KEY")).exists(_.nonEmpty) ||
      Option(System.getenv("LANGFUSE_SECRET_KEY")).exists(_.nonEmpty)

  "Llm4sConfig.tracing (Langfuse settings)" should {
    "provide defaults when not configured" in {
      // Skip test if Langfuse is configured in environment
      if (isLangfuseConfigured) {
        cancel("Test skipped: Langfuse is configured in environment (LANGFUSE_PUBLIC_KEY or LANGFUSE_SECRET_KEY)")
      }

      val ts  = Llm4sConfig.tracing().fold(err => fail(err.toString), identity)
      val cfg = ts.langfuse
      cfg.url shouldBe DefaultConfig.DEFAULT_LANGFUSE_URL
      cfg.env shouldBe DefaultConfig.DEFAULT_LANGFUSE_ENV
      cfg.release shouldBe DefaultConfig.DEFAULT_LANGFUSE_RELEASE
      cfg.version shouldBe DefaultConfig.DEFAULT_LANGFUSE_VERSION
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
        val ts  = Llm4sConfig.tracing().fold(err => fail(err.toString), identity)
        val cfg = ts.langfuse
        cfg.url shouldBe "https://example.com/api"
        cfg.publicKey shouldBe Some("pub")
        cfg.secretKey shouldBe Some("sec")
        cfg.env shouldBe "staging"
        cfg.release shouldBe "2.0.0"
        cfg.version shouldBe "2.1.3"
      }
    }
  }
}
