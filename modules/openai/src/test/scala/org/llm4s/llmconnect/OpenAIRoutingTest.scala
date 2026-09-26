package org.llm4s.llmconnect

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.*
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import scala.util.Using

/**
 * `LLMConnect` routes OpenAI, Azure and Requesty configs to `OpenAIClient` through the
 * registry, and refuses a mismatch.
 *
 * Moved from core's `LLMConnectProviderTypeSafetyTest`, `LLMConnectResultTest` and
 * `DefaultProviderIntegrationSpec` (#1132).
 */
class OpenAIRoutingTest extends AnyFunSuite with Matchers {
  private val registryService        = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService = registryService

  private def withProps(props: Map[String, String])(f: => Either[_, _]): Either[_, _] =
    Using.resource(SystemPropertiesOverride(props))(_ => f)

  private def withUnitProps(props: Map[String, String])(f: => Unit): Unit =
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

  test("OpenAI provider with OpenAIConfig returns OpenAIClient") {
    val cfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(ProviderId("openai"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Azure provider with AzureConfig returns OpenAIClient (Azure-backed)") {
    val cfg: ProviderConfig = AzureConfig(
      endpoint = "https://example.azure.com",
      apiKey = "key",
      model = "gpt-4o",
      apiVersion = "V2025_01_01_PREVIEW",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(ProviderId("azure"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("OpenAI provider with non-OpenAIConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = FixtureChatConfig(apiKey = "key", model = "fixture-model")

    val res = LLMConnect.getClient(ProviderId("openai"), wrongCfg)
    res.isLeft shouldBe true
  }

  test("Azure provider with non-AzureConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )

    val res = LLMConnect.getClient(ProviderId("azure"), wrongCfg)
    res.isLeft shouldBe true
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
      given ModelRegistryService = registryService
      Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
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
      given ModelRegistryService = registryService
      Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)
    }
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("Llm4sConfig.defaultProvider loads OpenAI config from named providers") {
    val props = Map(
      "llm4s.providers.provider"             -> "openai-main",
      "llm4s.providers.openai-main.provider" -> "openai",
      "llm4s.providers.openai-main.model"    -> "gpt-4o",
      "llm4s.providers.openai-main.apiKey"   -> "test-key"
    )

    withUnitProps(props) {
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

  test("Requesty provider with OpenAIConfig returns OpenAIClient") {
    val cfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "openai/gpt-4o-mini",
      organization = None,
      baseUrl = "https://router.requesty.ai/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    LLMConnect.getClient(ProviderId("requesty"), cfg) match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }
}
