package org.llm4s.llmconnect

import org.llm4s.config.Llm4sConfig
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import scala.util.Using

class LLMClientFactoryTest extends AnyFunSuite with Matchers {
  private val registryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

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

  test("LLMConnect.getClient returns DeepSeekClient for the default named DeepSeek provider") {
    val props = Map(
      "llm4s.providers.provider"               -> "deepseek-main",
      "llm4s.providers.deepseek-main.provider" -> "deepseek",
      "llm4s.providers.deepseek-main.model"    -> "deepseek-chat",
      "llm4s.providers.deepseek-main.apiKey"   -> "sk",
      "llm4s.providers.deepseek-main.baseUrl"  -> "https://api.deepseek.com"
    )

    withProps(props) {
      given ModelRegistryService = registryService
      val res                    = Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
      res match {
        case Right(client) => client.getClass.getSimpleName shouldBe "DeepSeekClient"
        case Left(err)     => fail(s"Expected Right, got Left($err)")
      }
    }
  }
}
