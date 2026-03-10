package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenRouterClient
import org.llm4s.testutil.CloudSmoke
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for OpenRouter.
 *
 * Tagged [[CloudSmoke]] — excluded from default `sbt test`.
 * Run with: `sbt testSmoke`
 * Requires: OPENROUTER_API_KEY environment variable.
 */
class OpenRouterSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("OPENROUTER_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): OpenAIConfig =
    OpenAIConfig.fromValues(
      modelName = "openai/gpt-4o-mini",
      apiKey = key,
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1"
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "OpenRouter" should "complete a basic request" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENROUTER_API_KEY not set")

    val client = new OpenRouterClient(config(apiKey.get))
    val result = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
  }

  it should "stream a response" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENROUTER_API_KEY not set")

    val client = new OpenRouterClient(config(apiKey.get))
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return AuthenticationError for invalid key" taggedAs CloudSmoke in {
    val client = new OpenRouterClient(config("sk-or-invalid-key-for-testing"))
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
