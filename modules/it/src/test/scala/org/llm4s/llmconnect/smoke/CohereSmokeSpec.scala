package org.llm4s.llmconnect.smoke

import org.scalatest.EitherValues
import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ CohereConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.CohereClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud

/**
 * Cloud smoke tests for Cohere.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `COHERE_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * `CohereClient` calls Cohere's OpenAI-compatibility API through `llm4s-openai-compatible`
 * (#1132), which is what gives it streaming. Until then `streamComplete` returned a
 * `Left(ConfigurationError)` (#925), and this suite checked that it did.
 */
@Cloud
class CohereSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("COHERE_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): CohereConfig =
    CohereConfig
      .fromValues(
        modelName = "command-a-03-2025",
        apiKey = key,
        baseUrl = CohereConfig.DEFAULT_BASE_URL
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Cohere" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "COHERE_API_KEY not set")

    val clientResult = CohereClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    val result = completion.toOption.get
    result.content should not be empty
    result.usage should not be empty
    result.usage.get.promptTokens should be > 0
    result.usage.get.completionTokens should be > 0
  }

  it should "stream a basic request" in {
    Tier.require(apiKey.isDefined, "COHERE_API_KEY not set")

    val client = CohereClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[String]
    val result = client.streamComplete(conversation, CompletionOptions(), _.content.foreach(chunks += _))

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    chunks.mkString should not be empty
    result.toOption.get.content shouldBe chunks.mkString
  }

  it should "return AuthenticationError for invalid key" in {
    val client = CohereClient(config("invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
