package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.llmconnect.config.{
  CohereConfig,
  DeepSeekConfig,
  MistralConfig,
  OpenAICompatibleConfig,
  OpenAIConfig,
  OpenAIWorkloadIdentity,
  ZaiConfig
}
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

/**
 * The OpenAI-compatible clients refuse a credential they cannot send rather than falling back to an
 * empty static key - which would go out as `Authorization: Bearer ` - or dropping a configured identity:
 * OpenRouter refuses `workloadIdentity`, and every client that needs a key refuses a blank one.
 */
class UnsupportedCredentialSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val identity = OpenAIWorkloadIdentity(
    identityToken = IdentitySource.Literal("jwt"),
    identityProviderId = "idp",
    serviceAccountId = "sa"
  )

  /** An OpenAI workload-identity config `OpenAIConfig.validate` accepts: `openai`, an OpenAI host, no key. */
  private val workloadIdentityConfig = OpenAIConfig(
    apiKey = "",
    model = "openai/gpt-4o",
    organization = None,
    baseUrl = "https://api.openai.com/v1",
    contextWindow = 8192,
    reserveCompletion = 4096,
    workloadIdentity = Some(identity)
  )

  private def openRouterConfig(apiKey: String) = OpenAIConfig(
    apiKey = apiKey,
    model = "openai/gpt-4o",
    organization = None,
    baseUrl = "https://openrouter.ai/api/v1",
    contextWindow = 8192,
    reserveCompletion = 4096
  )

  private def configurationError[A](result: Result[A]): ConfigurationError =
    result.left.value match
      case error: ConfigurationError => error
      case other                     => fail(s"expected a ConfigurationError, got $other")

  "OpenRouterClient" should {
    "refuse workloadIdentity from apply, naming the field" in {
      val error = configurationError(OpenRouterClient(workloadIdentityConfig))
      error.message should include("workloadIdentity")
      error.message should include("OpenRouter")
    }

    "refuse workloadIdentity from the three-argument apply" in {
      configurationError(
        OpenRouterClient(
          workloadIdentityConfig,
          org.llm4s.metrics.MetricsCollector.noop,
          org.llm4s.llmconnect.ProviderExchangeLogging.Disabled
        )
      ).message should include("workloadIdentity")
    }

    "refuse workloadIdentity from the constructor rather than send an empty bearer" in {
      val thrown = intercept[IllegalArgumentException](new OpenRouterClient(workloadIdentityConfig))
      thrown.getMessage should include("workloadIdentity")
    }

    "refuse workloadIdentity even with an OpenRouter base URL and a key" in {
      val both = openRouterConfig("sk-or").withWorkloadIdentity(Some(identity))
      configurationError(OpenRouterClient(both)).message should include("workloadIdentity")
      intercept[IllegalArgumentException](new OpenRouterClient(both))
    }

    "refuse a blank apiKey from apply and the constructor" in {
      configurationError(OpenRouterClient(openRouterConfig("  "))).message should include("apiKey")
      intercept[IllegalArgumentException](new OpenRouterClient(openRouterConfig(""))).getMessage should include(
        "apiKey"
      )
    }

    "accept a config with a key" in {
      OpenRouterClient(openRouterConfig("sk-or")).isRight shouldBe true
    }
  }

  "the fixed-key OpenAI-compatible clients" should {
    val url = "https://api.example/v1"

    "refuse a blank apiKey from apply with a ConfigurationError" in {
      configurationError(DeepSeekClient(DeepSeekConfig("", "deepseek-chat", url, 8192, 1024))).message should include(
        "apiKey"
      )
      configurationError(ZaiClient(ZaiConfig(" ", "GLM-4.7", url, 8192, 1024))).message should include("apiKey")
      configurationError(MistralClient(MistralConfig("", "mistral-small", url, 8192, 1024))).message should include(
        "apiKey"
      )
      configurationError(CohereClient(CohereConfig("", "command-r", url, 8192, 1024))).message should include(
        "apiKey"
      )
    }

    "refuse a blank apiKey from the constructor" in {
      intercept[IllegalArgumentException](new DeepSeekClient(DeepSeekConfig("", "deepseek-chat", url, 8192, 1024)))
      intercept[IllegalArgumentException](new ZaiClient(ZaiConfig("", "GLM-4.7", url, 8192, 1024)))
      intercept[IllegalArgumentException](new MistralClient(MistralConfig("", "mistral-small", url, 8192, 1024)))
      intercept[IllegalArgumentException](new CohereClient(CohereConfig("", "command-r", url, 8192, 1024)))
    }

    "accept a key" in {
      DeepSeekClient(DeepSeekConfig("sk", "deepseek-chat", url, 8192, 1024)).isRight shouldBe true
      ZaiClient(ZaiConfig("sk", "GLM-4.7", url, 8192, 1024)).isRight shouldBe true
      MistralClient(MistralConfig("sk", "mistral-small", url, 8192, 1024)).isRight shouldBe true
      CohereClient(CohereConfig("sk", "command-r", url, 8192, 1024)).isRight shouldBe true
    }
  }

  "OpenAICompatibleClient" should {
    def settings(credential: OpenAICompatibleClient.Credential) =
      OpenAICompatibleClient.Settings("p", "P", "m", "https://api.example/v1", credential, 8192, 1024)

    "refuse a blank Static credential, built by hand" in {
      val thrown = intercept[IllegalArgumentException](
        new OpenAICompatibleClient(
          settings(OpenAICompatibleClient.Credential.Static(" ")),
          OpenAICompatibleDialect.Standard
        )
      )
      thrown.getMessage should include("apiKey")
    }

    "treat a generic config's blank apiKey as none, sending no Authorization header" in {
      OpenAICompatibleClient
        .settings(OpenAICompatibleConfig(model = "m", baseUrl = "https://x.example/v1", apiKey = Some("  ")))
        .credential shouldBe OpenAICompatibleClient.Credential.Anonymous

      var seen = Map.empty[String, String]
      withServer("/chat/completions") { (exchange: HttpExchange) =>
        seen = exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
        sendJsonResponse(exchange, 200, openAICompletion("ok", "m"))
      } { baseUrl =>
        OpenAICompatibleClient(OpenAICompatibleConfig(model = "m", baseUrl = baseUrl, apiKey = Some(""))).value
          .complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
          .isRight shouldBe true
      }
      seen.keySet should not contain "authorization"
    }
  }
