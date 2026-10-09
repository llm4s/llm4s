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
        // api.z.ai's free flash model (listed in the model registry as `zai/glm-4.5-flash`).
        // `GLM-4-Flash` is a bigmodel.cn name that api.z.ai does not serve.
        modelName = "glm-4.5-flash",
        apiKey = key,
        baseUrl = ZaiConfig.DEFAULT_BASE_URL
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  // GLM-4.5 models reason before answering by default and the reasoning counts against the
  // budget, so leave room for it: 16 tokens could be spent entirely on thinking.
  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(512))

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

    // The first delta carries `content: ""`, so `isDefined` alone passes for a stream with no
    // payload; require real text or reasoning text, and that the text is what the call returned.
    chunks.exists(c => c.content.exists(_.nonEmpty) || c.thinkingDelta.exists(_.nonEmpty)) shouldBe true
    chunks.flatMap(_.content).mkString shouldBe result.content
  }

  it should "return AuthenticationError for invalid key" in {
    val client = ZaiClient(config("invalid-zai-key-for-testing")).value
    val result = client.complete(conversation, options)

    result.swap.value shouldBe an[AuthenticationError]
  }
}
