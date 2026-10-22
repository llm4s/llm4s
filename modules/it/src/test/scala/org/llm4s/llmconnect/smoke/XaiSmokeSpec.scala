package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, XaiConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.XaiClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for xAI (Grok), #1023.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires: `XAI_API_KEY` environment variable; `XAI_MODEL` optionally overrides the model
 * (default `grok-4.7`). Tier: `@Cloud` - `sbt testSmoke`.
 *
 * `XaiClient` is the xAI dialect of `llm4s-openai-compatible`, so streaming goes through the
 * shared client. Assertions are structural; generated text is never compared.
 */
@Cloud
class XaiSmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("XAI_API_KEY")).filter(_.nonEmpty)
  private val model: String          = Option(System.getenv("XAI_MODEL")).filter(_.nonEmpty).getOrElse("grok-4.7")

  private def config(key: String): XaiConfig =
    XaiConfig.fromValues(modelName = model, apiKey = key, baseUrl = XaiConfig.DEFAULT_BASE_URL).value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  // Room for a reasoning model's answer; xAI bills reasoning tokens outside completion_tokens.
  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(256))

  "xAI" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "XAI_API_KEY not set")

    val client     = XaiClient(config(apiKey.get)).value
    val completion = client.complete(conversation, options).value

    completion.content should not be empty
    completion.usage shouldBe defined
    completion.usage.get.promptTokens should be > 0
    completion.usage.get.completionTokens should be > 0
  }

  it should "stream a basic request" in {
    Tier.require(apiKey.isDefined, "XAI_API_KEY not set")

    val client = XaiClient(config(apiKey.get)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, options, c => chunks += c).value

    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.content
  }

  it should "return AuthenticationError for an invalid API key" in {
    val client = XaiClient(config("invalid-key-for-testing")).value
    val result = client.complete(conversation, options)

    result.swap.value shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "xAI"
  override protected def apiKeyEnvVar: String                           = "XAI_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = XaiClient(config(key))

  registerCapabilityContract()
}
