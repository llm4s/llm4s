package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenRouterClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for OpenRouter.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `OPENROUTER_API_KEY` environment variable.
 */
class OpenRouterSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("OPENROUTER_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): OpenAIConfig =
    OpenAIConfig.fromValues(
      modelName = "openai/gpt-4o-mini",
      apiKey = key,
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1"
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "OpenRouter" should "complete a basic request" in {
    assume(apiKey.isDefined, "OPENROUTER_API_KEY not set")

    val client     = new OpenRouterClient(config(apiKey.get))
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "stream a response" in {
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

  it should "return AuthenticationError for invalid key" in {
    val client = new OpenRouterClient(config("sk-or-invalid-key-for-testing"))
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
