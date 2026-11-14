package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.ProviderModuleChecks
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The DeepSeek, Z.ai, Mistral, Cohere and xAI configs carry an `ApiKeyProvider` (#2477): set, it supplies the key, so
 * a config with no `apiKey` of its own builds a client; cleared, the blank key is refused as before.
 */
class DialectApiKeyProviderSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry

  private val keys = ApiKeyProvider.static(ApiKey("sk-from-provider"))

  /** A dialect: its config with a blank key and the given provider, and its client built from that config. */
  final private case class Dialect(
    name: String,
    config: Option[ApiKeyProvider] => ProviderConfig,
    client: Option[ApiKeyProvider] => Result[LLMClient]
  )

  private def deepSeek(k: Option[ApiKeyProvider]) = DeepSeekConfig(Secret(""), "deepseek-chat").withApiKeyProvider(k)
  private def zai(k: Option[ApiKeyProvider])      = ZaiConfig(Secret(""), "glm-4.6").withApiKeyProvider(k)
  private def mistral(k: Option[ApiKeyProvider]) =
    MistralConfig(Secret(""), "mistral-small-latest").withApiKeyProvider(k)
  private def cohere(k: Option[ApiKeyProvider]) = CohereConfig(Secret(""), "command-a-03-2025").withApiKeyProvider(k)
  private def xai(k: Option[ApiKeyProvider])    = XaiConfig(Secret(""), "grok-4").withApiKeyProvider(k)

  private val dialects = Seq(
    Dialect("DeepSeek", deepSeek, k => DeepSeekClient(deepSeek(k))),
    Dialect("Z.ai", zai, k => ZaiClient(zai(k))),
    Dialect("Mistral", mistral, k => MistralClient(mistral(k))),
    Dialect("Cohere", cohere, k => CohereClient(cohere(k))),
    Dialect("xAI", xai, k => XaiClient(xai(k)))
  )

  "each dialect config" should "build a client from its ApiKeyProvider alone, and refuse a blank key without one" in {
    dialects.foreach { d =>
      withClue(s"${d.name}: ") {
        d.config(Some(keys)).apiKeyProvider shouldBe Some(keys)
        d.config(Some(keys)).toString should include("apiKeyProvider=ApiKeyProvider.static(***)")
        d.config(None).apiKeyProvider shouldBe None
        (d.config(None).toString should not).include("apiKeyProvider")
        d.client(Some(keys)).map(_.close()) shouldBe Right(())
        d.client(None).left.map(_.message).swap.toOption.getOrElse("") should include("apiKey must be non-empty")
      }
    }
  }

  it should "take an ApiKeyProvider through the ProviderConfig setter the loader calls for apiKeyFile" in {
    dialects.foreach { d =>
      withClue(s"${d.name}: ")(d.config(None).withApiKeyProvider(keys).apiKeyProvider shouldBe Some(keys))
    }
  }
}
