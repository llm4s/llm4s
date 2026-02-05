package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }

/**
 * Tests for GeminiClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class GeminiClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: GeminiConfig = GeminiConfig(
    apiKey = "test-api-key-for-closed-state-testing",
    model = "gemini-2.0-flash",
    baseUrl = "https://example.invalid/v1beta",
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "GeminiClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new GeminiClient(createTestConfig)

    // Close the client
    client.close()

    // Attempt to call complete()
    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("gemini-2.0-flash")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new GeminiClient(createTestConfig)
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
    val client = new GeminiClient(createTestConfig)

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
    val config = createTestConfig.copy(model = "gemini-1.5-pro")
    val client = new GeminiClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("gemini-1.5-pro")
  }
}
