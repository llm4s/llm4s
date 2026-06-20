package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, XAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.XAIClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for xAI (Grok).
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `XAI_API_KEY` environment variable.
 * Skips gracefully when the API key is absent.
 *
 * Tagged: CloudSmoke
 */
class XAISmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver    = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("XAI_API_KEY")).filter(_.nonEmpty)

  private def config(key: String): XAIConfig =
    XAIConfig.fromValues(
      modelName = "grok-beta",
      apiKey = key,
      baseUrl = XAIConfig.DEFAULT_BASE_URL,
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "XAI Grok" should "complete a basic request" in {
    assume(apiKey.isDefined, "XAI_API_KEY not set — skipping smoke test")

    val clientResult = XAIClient(config(apiKey.get))
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

  it should "populate token usage in a successful completion" in {
    assume(apiKey.isDefined, "XAI_API_KEY not set — skipping smoke test")

    val client     = XAIClient(config(apiKey.get)).toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    completion.isRight shouldBe true
    val c = completion.toOption.get
    c.usage shouldBe defined
    c.usage.get.promptTokens should be > 0
    c.usage.get.completionTokens should be > 0
    c.usage.get.totalTokens should be > 0
  }

  it should "stream a response and emit chunks" in {
    assume(apiKey.isDefined, "XAI_API_KEY not set — skipping smoke test")

    val client = XAIClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return AuthenticationError for an invalid API key" in {
    val client = XAIClient(config("xai-invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }
}
