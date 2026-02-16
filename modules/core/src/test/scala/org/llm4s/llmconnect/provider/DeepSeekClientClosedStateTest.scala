package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }

/**
 * Tests for DeepSeekClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class DeepSeekClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: DeepSeekConfig = DeepSeekConfig(
    apiKey = "test-api-key-for-closed-state-testing",
    model = "deepseek-chat",
    baseUrl = "https://example.invalid/v1",
    contextWindow = 65536,
    reserveCompletion = 4096
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "DeepSeekClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new DeepSeekClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("deepseek-chat")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new DeepSeekClient(createTestConfig)
    var chunksReceived = 0

    client.close()

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    chunksReceived shouldBe 0
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new DeepSeekClient(createTestConfig)

    noException should be thrownBy {
      client.close()
      client.close()
      client.close()
    }

    val result = client.complete(createTestConversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "include model name in the closed error message" in {
    val config = createTestConfig.copy(model = "deepseek-reasoner")
    val client = new DeepSeekClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("deepseek-reasoner")
  }
}
