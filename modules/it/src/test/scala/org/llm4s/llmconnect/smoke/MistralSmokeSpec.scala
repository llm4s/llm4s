package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, MistralConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.MistralClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Mistral.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires: `MISTRAL_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * `MistralClient` is the Mistral dialect of `llm4s-openai-compatible` (#1210), so streaming
 * goes through the shared client. Assertions are structural; generated text is never compared.
 */
@Cloud
class MistralSmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("MISTRAL_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): MistralConfig =
    MistralConfig
      .fromValues(
        modelName = "mistral-small-latest",
        apiKey = key,
        baseUrl = MistralConfig.DEFAULT_BASE_URL
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(16))

  "Mistral" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client     = MistralClient(config(apiKey.get)).value
    val completion = client.complete(conversation, options).value

    completion.usage shouldBe defined
    completion.usage.get.promptTokens should be > 0
    completion.usage.get.completionTokens should be > 0
  }

  it should "stream a basic request" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client = MistralClient(config(apiKey.get)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, options, c => chunks += c).value

    // The first delta carries `content: ""` (the role chunk), so `isDefined` alone would pass for a
    // stream with no text at all; require real text, and that it is what the call returned.
    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.content
  }

  it should "return AuthenticationError for an invalid API key" in {
    val client = MistralClient(config("invalid-key-for-testing")).value
    val result = client.complete(conversation, options)

    result.swap.value shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "Mistral"
  override protected def apiKeyEnvVar: String                           = "MISTRAL_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = MistralClient(config(key))

  registerCapabilityContract()
}
