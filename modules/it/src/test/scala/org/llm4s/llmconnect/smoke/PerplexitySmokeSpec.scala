package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.PerplexityConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.PerplexityClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Perplexity's Agent API (`llm4s-perplexity`), #1026.
 *
 * Requires: `PERPLEXITY_API_KEY`; `PERPLEXITY_MODEL` optionally overrides the model (default `perplexity/sonar`).
 * Tier: `@Cloud` - `sbt testSmoke`. Each request offers the `web_search` tool, so a run is billed for its searches
 * as well as its tokens. Assertions are structural; generated text is never compared.
 */
@Cloud
class PerplexitySmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val apiKey: Option[String] = Option(System.getenv("PERPLEXITY_API_KEY")).filter(_.nonEmpty)
  private val model: String =
    Option(System.getenv("PERPLEXITY_MODEL")).filter(_.nonEmpty).getOrElse("perplexity/sonar")

  private def config(key: String): PerplexityConfig = PerplexityConfig.fromValues(model, Secret(key)).value

  private def conversation: Conversation =
    Conversation(Seq(UserMessage("In one sentence, what is the latest stable Scala 3 release?")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(512))

  "Perplexity" should "complete a request with usage and the sources it searched" in {
    Tier.require(apiKey.isDefined, "PERPLEXITY_API_KEY not set")

    val completion = PerplexityClient(config(apiKey.get)).value.complete(conversation, options).value

    completion.content should not be empty
    completion.usage shouldBe defined
    completion.usage.get.promptTokens should be > 0
    completion.usage.get.completionTokens should be > 0
    completion.citations should not be empty
  }

  it should "stream a request" in {
    Tier.require(apiKey.isDefined, "PERPLEXITY_API_KEY not set")

    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result =
      PerplexityClient(config(apiKey.get)).value.streamComplete(conversation, options, c => chunks += c).value

    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    result.content should not be empty
    result.usage shouldBe defined
  }

  it should "return AuthenticationError for an invalid API key" in {
    Tier.require(apiKey.isDefined, "PERPLEXITY_API_KEY not set")
    PerplexityClient(config("invalid-key-for-testing")).value.complete(conversation, options).swap.value shouldBe
      an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "Perplexity"
  override protected def apiKeyEnvVar: String                           = "PERPLEXITY_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = PerplexityClient(config(key))

  registerCapabilityContract()
}
