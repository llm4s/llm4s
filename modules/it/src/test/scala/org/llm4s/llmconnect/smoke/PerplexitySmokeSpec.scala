package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, PerplexityConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.PerplexityClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for the Perplexity AI Sonar provider.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with:
 *   sbt "it/testOnly org.llm4s.llmconnect.smoke.*"
 * or the `sbt testSmoke` alias.
 *
 * Requires: `PERPLEXITY_API_KEY` environment variable.
 *
 * Supported models tested:
 *   - `sonar` (cheapest) for both complete() and streamComplete()
 */
class PerplexitySmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("PERPLEXITY_API_KEY")).filter(_.nonEmpty)

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver    = ContextWindowResolver(mrs)

  private def config(key: String): PerplexityConfig =
    PerplexityConfig.fromValues(
      modelName = "sonar",
      apiKey = key,
      baseUrl = PerplexityConfig.DEFAULT_BASE_URL
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "Perplexity" should "complete a basic request using sonar model" in {
    assume(apiKey.isDefined, "PERPLEXITY_API_KEY not set — skipping smoke test")

    val clientResult = PerplexityClient(config(apiKey.get))
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
    result.usage shouldBe defined
    result.usage.get.promptTokens should be > 0
    result.usage.get.completionTokens should be > 0
    result.usage.get.totalTokens should be > 0
  }

  it should "stream a response using sonar model" in {
    assume(apiKey.isDefined, "PERPLEXITY_API_KEY not set — skipping smoke test")

    val client = PerplexityClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return AuthenticationError for invalid API key" in {
    val client = PerplexityClient(
      PerplexityConfig(
        apiKey = "pplx-invalid-key-for-testing",
        model = "sonar",
        baseUrl = PerplexityConfig.DEFAULT_BASE_URL,
        contextWindow = 128000,
        reserveCompletion = 4096
      )
    ).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
