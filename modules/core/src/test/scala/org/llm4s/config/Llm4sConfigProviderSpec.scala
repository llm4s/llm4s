package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.{ AnthropicConfig, DeepSeekConfig, MistralConfig, OpenAIConfig }
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

    "load Anthropic config via system properties" in {
      val props = Map(
        "llm4s.llm.model"        -> "anthropic/claude-3-5-sonnet-latest",
        "llm4s.anthropic.apiKey" -> "sk-ant-test-key"
      )

      withProps(props) {
        val cfg = Llm4sConfig.provider().fold(err => fail(err.toString), identity)

        cfg match {
          case anthropic: AnthropicConfig =>
            anthropic.model shouldBe "claude-3-5-sonnet-latest"
            anthropic.apiKey shouldBe "sk-ant-test-key"
            anthropic.baseUrl shouldBe DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL
          case other =>
            fail(s"Expected AnthropicConfig, got $other")
        }
      }
    }

    "load DeepSeek config via system properties" in {
      val props = Map(
        "llm4s.llm.model"       -> "deepseek/deepseek-chat",
        "llm4s.deepseek.apiKey" -> "ds-test-key"
      )

      withProps(props) {
        val cfg = Llm4sConfig.provider().fold(err => fail(err.toString), identity)

        cfg match {
          case deepseek: DeepSeekConfig =>
            deepseek.model shouldBe "deepseek-chat"
            deepseek.apiKey shouldBe "ds-test-key"
            deepseek.baseUrl shouldBe DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL
          case other =>
            fail(s"Expected DeepSeekConfig, got $other")
        }
      }
    }

    "load Mistral config via system properties" in {
      val props = Map(
        "llm4s.llm.model"      -> "mistral/mistral-large-latest",
        "llm4s.mistral.apiKey" -> "mistral-test-key"
      )

      withProps(props) {
        val cfg = Llm4sConfig.provider().fold(err => fail(err.toString), identity)

        cfg match {
          case mistral: MistralConfig =>
            mistral.model shouldBe "mistral-large-latest"
            mistral.apiKey shouldBe "mistral-test-key"
            mistral.baseUrl shouldBe MistralConfig.DEFAULT_BASE_URL
          case other =>
            fail(s"Expected MistralConfig, got $other")
        }
      }
    }

    "return ConfigurationError for blank model" in {
      val props = Map(
        "llm4s.llm.model"     -> "   ",
        "llm4s.openai.apiKey" -> "test-key"
      )

      withProps(props) {
        val result = Llm4sConfig.provider()
        result.isLeft shouldBe true
        result.left.getOrElse(fail("expected Left")).message should include("Missing model spec")
      }
    }
  }
}
