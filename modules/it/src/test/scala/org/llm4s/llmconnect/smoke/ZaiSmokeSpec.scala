package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ZaiConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.ZaiClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Z.ai (Zai).
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires: `ZAI_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * Z.ai sends message text as arrays of typed parts and may return either shape; the
 * shared `llm4s-openai-compatible` client handles both. Assertions are structural and
 * generated text is never compared.
 */
@Cloud
class ZaiSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("ZAI_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): ZaiConfig =
    ZaiConfig
      .fromValues(
        modelName = "GLM-4-Flash",
        apiKey = key,
        baseUrl = ZaiConfig.DEFAULT_BASE_URL
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(16))

  "Zai" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "ZAI_API_KEY not set")

    val client     = ZaiClient(config(apiKey.get)).value
    val completion = client.complete(conversation, options).value

    completion.usage shouldBe defined
    completion.usage.get.promptTokens should be > 0
    completion.usage.get.completionTokens should be > 0
  }

  it should "stream a response" in {
    Tier.require(apiKey.isDefined, "ZAI_API_KEY not set")

    val client = ZaiClient(config(apiKey.get)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, options, c => chunks += c).value

    chunks.exists(_.content.isDefined) shouldBe true
    chunks.flatMap(_.content).mkString shouldBe result.content
  }

  it should "return AuthenticationError for invalid key" in {
    val client = ZaiClient(config("invalid-zai-key-for-testing")).value
    val result = client.complete(conversation, options)

    result.swap.value shouldBe an[AuthenticationError]
  }
}
