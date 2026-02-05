package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }

/**
 * Tests for AnthropicClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class AnthropicClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: AnthropicConfig = AnthropicConfig(
    apiKey = "test-api-key-for-closed-state-testing",
    model = "claude-sonnet-4-5-latest",
    baseUrl = "https://example.invalid/v1",
    contextWindow = 200000,
    reserveCompletion = 8192
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "AnthropicClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new AnthropicClient(createTestConfig)

    // Close the client
    client.close()

    // Attempt to call complete()
    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("claude-sonnet-4-5-latest")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new AnthropicClient(createTestConfig)
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
    val client = new AnthropicClient(createTestConfig)

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
    val config = createTestConfig.copy(model = "claude-opus-4-5")
    val client = new AnthropicClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("claude-opus-4-5")
  }
}
