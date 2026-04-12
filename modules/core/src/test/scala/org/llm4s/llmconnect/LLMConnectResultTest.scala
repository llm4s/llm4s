package org.llm4s.llmconnect

import org.llm4s.config.Llm4sConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import scala.util.Using

class LLMConnectResultTest extends AnyFunSuite with Matchers {

  private def withProps(props: Map[String, String])(f: => Either[_, _]): Either[_, _] =
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

  test("getClient returns OpenAIClient for the default named OpenAI provider") {
    val props = Map(
      "llm4s.providers.provider"             -> "openai-main",
      "llm4s.providers.openai-main.provider" -> "openai",
      "llm4s.providers.openai-main.model"    -> "gpt-4o",
      "llm4s.providers.openai-main.apiKey"   -> "sk",
      "llm4s.providers.openai-main.baseUrl"  -> "https://api.openai.com/v1"
    )

    val res = withProps(props) {
      Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("getClient returns OpenRouterClient for the default named OpenRouter provider") {
    val props = Map(
      "llm4s.providers.provider"                 -> "openrouter-main",
      "llm4s.providers.openrouter-main.provider" -> "openrouter",
      "llm4s.providers.openrouter-main.model"    -> "gpt-4o",
      "llm4s.providers.openrouter-main.apiKey"   -> "sk",
      "llm4s.providers.openrouter-main.baseUrl"  -> "https://openrouter.ai/api/v1"
    )

    val res = withProps(props) {
      Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenRouterClient"
  }

  test("getClient returns OpenAIClient for the default named Azure provider") {
    val props = Map(
      "llm4s.providers.provider"            -> "azure-main",
      "llm4s.providers.azure-main.provider" -> "azure",
      "llm4s.providers.azure-main.model"    -> "gpt-4o",
      "llm4s.providers.azure-main.endpoint" -> "https://example.azure.com",
      "llm4s.providers.azure-main.apiKey"   -> "az-sk"
    )

    val res = withProps(props) {
      Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }
}
