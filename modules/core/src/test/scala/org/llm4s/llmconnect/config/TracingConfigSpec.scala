package org.llm4s.llmconnect.config

import org.llm4s.config.ConfigReader
import org.llm4s.config.DefaultConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TracingConfigSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  "ConfigReader.TracingConf (Langfuse settings)" should {
    "provide defaults when not configured" in {
      val ts  = ConfigReader.TracingConf().fold(err => fail(err.toString), identity)
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
        val ts  = ConfigReader.TracingConf().fold(err => fail(err.toString), identity)
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
