package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }

/**
 * Tests for OpenRouterClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class OpenRouterClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: OpenAIConfig = OpenAIConfig.fromValues(
    modelName = "openai/gpt-4",
    apiKey = "test-api-key-for-closed-state-testing",
    organization = None,
    // Must never be used by unit tests (no network). We keep a clearly fake endpoint.
    baseUrl = "https://example.invalid/api/v1"
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "OpenRouterClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new OpenRouterClient(createTestConfig)

    // Close the client
    client.close()

    // Attempt to call complete()
    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("openai/gpt-4")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new OpenRouterClient(createTestConfig)
    var chunksReceived = 0

    // Close the client
    client.close()

    // Attempt to call streamComplete()
    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    chunksReceived shouldBe 0 // No chunks should be emitted
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new OpenRouterClient(createTestConfig)

    // Close multiple times - should not throw
    noException should be thrownBy {
      client.close()
      client.close()
      client.close()
    }

    // Verify client is still closed and returns error
    val result = client.complete(createTestConversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "include model name in the closed error message" in {
    val config = OpenAIConfig.fromValues(
      modelName = "anthropic/claude-3-opus",
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/api/v1"
    )
    val client = new OpenRouterClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("anthropic/claude-3-opus")
  }
}
