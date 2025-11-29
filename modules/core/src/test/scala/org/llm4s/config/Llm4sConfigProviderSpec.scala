package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.OpenAIConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.provider under controlled configuration.
 *
 * We set system properties explicitly to avoid interference from other
 * modules' application.conf files or developer environment overrides.
 */
class Llm4sConfigProviderSpec extends AnyWordSpec with Matchers {

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

  "Llm4sConfig.provider" should {
    "load OpenAI config from llm4s.* defaults" in {
      val props = Map(
        "llm4s.llm.model"     -> "openai/gpt-4o",
        "llm4s.openai.apiKey" -> "test-key"
      )

      withProps(props) {
        val cfg = Llm4sConfig.provider().fold(err => fail(err.toString), identity)

        cfg match {
          case openai: OpenAIConfig =>
            openai.model shouldBe "gpt-4o"
            openai.apiKey shouldBe "test-key"
          case other =>
            fail(s"Expected OpenAIConfig, got $other")
        }
      }
    }
  }
}
