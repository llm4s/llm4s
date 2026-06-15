package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ZaiConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.ZaiClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Z.ai (Zai).
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `ZAI_API_KEY` environment variable.
 */
class ZaiSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver    = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("ZAI_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): ZaiConfig =
    ZaiConfig.fromValues(
      modelName = "GLM-4-Flash",
      apiKey = key,
      baseUrl = ZaiConfig.DEFAULT_BASE_URL
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Zai" should "complete a basic request" in {
    assume(apiKey.isDefined, "ZAI_API_KEY not set")

    val clientResult = ZaiClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
    completion.toOption.get.usage shouldBe defined
  }

  it should "stream a response" in {
    assume(apiKey.isDefined, "ZAI_API_KEY not set")

    val client = ZaiClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return AuthenticationError for invalid key" in {
    val client = ZaiClient(config("invalid-zai-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
