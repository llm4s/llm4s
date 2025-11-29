package org.llm4s.llmconnect

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.Llm4sConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMConnectResultTest extends AnyFunSuite with Matchers {

  test("getClientResult returns OpenAIClient for openai/ model") {
    val props = Map(
      "llm4s.llm.model"      -> "openai/gpt-4o",
      "llm4s.openai.apiKey"  -> "sk",
      "llm4s.openai.baseUrl" -> "https://api.openai.com/v1"
    )
    val res = withProps(props) {
      Llm4sConfig.provider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("getClientResult returns OpenRouterClient when base URL is OpenRouter") {
    val props = Map(
      "llm4s.llm.model"      -> "openrouter/gpt-4o",
      "llm4s.openai.apiKey"  -> "sk",
      "llm4s.openai.baseUrl" -> "https://openrouter.ai/api/v1"
    )

    val res = withProps(props) {
      Llm4sConfig.provider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenRouterClient"
  }

  test("getClientResult returns OpenAIClient for Azure provider") {
    val props = Map(
      "llm4s.llm.model"      -> "azure/gpt-4o",
      "llm4s.azure.endpoint" -> "https://example.azure.com",
      "llm4s.azure.apiKey"   -> "az-sk"
    )

    val res = withProps(props) {
      Llm4sConfig.provider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  private def withProps(props: Map[String, String])(f: => Either[_, _]): Either[_, _] = {
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
}
