package org.llm4s.llmconnect

import org.llm4s.config.Llm4sConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import scala.util.Using

class LLMClientFactoryTest extends AnyFunSuite with Matchers {

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

  test("LLMConnect.getClient returns OpenAIClient for the default named OpenAI provider") {
    val props = Map(
      "llm4s.providers.provider"             -> "openai-main",
      "llm4s.providers.openai-main.provider" -> "openai",
      "llm4s.providers.openai-main.model"    -> "gpt-4o",
      "llm4s.providers.openai-main.apiKey"   -> "sk",
      "llm4s.providers.openai-main.baseUrl"  -> "https://api.openai.com/v1"
    )

    withProps(props) {
      val res = Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
      res match {
        case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
        case Left(err)     => fail(s"Expected Right, got Left($err)")
      }
    }
  }

  test("LLMConnect.getClient returns AnthropicClient for the default named Anthropic provider") {
    val props = Map(
      "llm4s.providers.provider"                -> "anthropic-main",
      "llm4s.providers.anthropic-main.provider" -> "anthropic",
      "llm4s.providers.anthropic-main.model"    -> "claude-3-sonnet",
      "llm4s.providers.anthropic-main.apiKey"   -> "sk-anthropic",
      "llm4s.providers.anthropic-main.baseUrl"  -> "https://api.anthropic.com"
    )

    withProps(props) {
      val res = Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
      res match {
        case Right(client) => client.getClass.getSimpleName shouldBe "AnthropicClient"
        case Left(err)     => fail(s"Expected Right, got Left($err)")
      }
    }
  }
}
