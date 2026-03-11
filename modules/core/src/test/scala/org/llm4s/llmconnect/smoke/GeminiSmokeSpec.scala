package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.GeminiClient
import org.llm4s.testutil.CloudSmoke
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Google Gemini.
 *
 * Tagged [[CloudSmoke]] — excluded from default `sbt test`.
 * Run with: `sbt testSmoke`
 * Requires: GOOGLE_API_KEY environment variable.
 */
class GeminiSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("GOOGLE_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): GeminiConfig =
    GeminiConfig.fromValues(
      modelName = "gemini-2.0-flash",
      apiKey = key,
      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Gemini" should "complete a basic request" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "GOOGLE_API_KEY not set")

    val client = new GeminiClient(config(apiKey.get))
    val result = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
  }

  it should "stream a response" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "GOOGLE_API_KEY not set")

    val client = new GeminiClient(config(apiKey.get))
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return an error for invalid key" taggedAs CloudSmoke in {
    val client = new GeminiClient(config("invalid-api-key-for-testing"))
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
  }
}
