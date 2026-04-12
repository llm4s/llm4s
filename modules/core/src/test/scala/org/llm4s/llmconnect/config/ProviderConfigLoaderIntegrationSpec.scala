package org.llm4s.llmconnect.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.Llm4sConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

class DefaultProviderIntegrationSpec extends AnyWordSpec with Matchers {
  private def withProps(props: Map[String, String])(f: => Unit): Unit =
    Using.resource(SystemPropertiesOverride(props))(_ => f)

  final private case class SystemPropertiesOverride(props: Map[String, String]) extends AutoCloseable {
    private val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap

    props.foreach { case (k, v) => System.setProperty(k, v) }
    ConfigFactory.invalidateCaches()

    override def close(): Unit = {
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
  }

  "Llm4sConfig.defaultProvider" should {
    "load OpenAI config from named providers" in {
      val props = Map(
        "llm4s.providers.provider"             -> "openai-main",
        "llm4s.providers.openai-main.provider" -> "openai",
        "llm4s.providers.openai-main.model"    -> "gpt-4o",
        "llm4s.providers.openai-main.apiKey"   -> "test-key"
      )

      withProps(props) {
        val prov = Llm4sConfig.defaultProvider().fold(err => fail(err.toString), identity)
        prov match {
          case openai: OpenAIConfig =>
            openai.model shouldBe "gpt-4o"
            openai.apiKey shouldBe "test-key"
            openai.baseUrl should startWith("https://api.openai.com/")
          case other => fail(s"Expected OpenAIConfig, got $other")
        }
      }
    }

    "load Mistral config from the configured default named provider" in {
      val props = Map(
        "llm4s.providers.provider"              -> "mistral-main",
        "llm4s.providers.mistral-main.provider" -> "mistral",
        "llm4s.providers.mistral-main.model"    -> "mistral-small-latest",
        "llm4s.providers.mistral-main.apiKey"   -> "sys-test-key"
      )

      withProps(props) {
        val prov = Llm4sConfig.defaultProvider().fold(err => fail(err.toString), identity)
        prov match {
          case mistral: MistralConfig =>
            mistral.model shouldBe "mistral-small-latest"
            mistral.apiKey shouldBe "sys-test-key"
            mistral.baseUrl should startWith("https://api.mistral.ai")
          case other => fail(s"Expected MistralConfig, got $other")
        }
      }
    }
  }
}
