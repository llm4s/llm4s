package org.llm4s.llmconnect

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.Llm4sConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMClientFactoryTest extends AnyFunSuite with Matchers {

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

  test("LLMConnect.getClientResult returns OpenAIClient for openai/ prefix with API key") {
    val props = Map(
      "llm4s.llm.model"      -> "openai/gpt-4o",
      "llm4s.openai.apiKey"  -> "sk",
      "llm4s.openai.baseUrl" -> "https://api.openai.com/v1"
    )

    withProps(props) {
      val res = Llm4sConfig.provider().flatMap(LLMConnect.getClient)
      res match {
        case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
        case Left(err)     => fail(s"Expected Right, got Left($err)")
      }
    }
  }

  test("LLMConnect.getClientResult returns AnthropicClient for anthropic/ prefix with API key") {
    val props = Map(
      "llm4s.llm.model"         -> "anthropic/claude-3-sonnet",
      "llm4s.anthropic.apiKey"  -> "sk-anthropic",
      "llm4s.anthropic.baseUrl" -> "https://api.anthropic.com"
    )

    withProps(props) {
      val res = Llm4sConfig.provider().flatMap(LLMConnect.getClient)
      res match {
        case Right(client) => client.getClass.getSimpleName shouldBe "AnthropicClient"
        case Left(err)     => fail(s"Expected Right, got Left($err)")
      }
    }
  }
}
