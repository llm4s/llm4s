package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, GeminiConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.GeminiClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Gemini.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `GEMINI_API_KEY` environment variable.
 */
class GeminiSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("GEMINI_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): GeminiConfig =
    GeminiConfig.fromValues(
      modelName = "gemini-1.5-flash",
      apiKey = key,
      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Gemini" should "complete a basic request" in {
    assume(apiKey.isDefined, "GEMINI_API_KEY not set")

    val clientResult = GeminiClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "stream a response" in {
    assume(apiKey.isDefined, "GEMINI_API_KEY not set")

    val client = GeminiClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return an error for invalid key" in {
    val client = GeminiClient(config("invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
