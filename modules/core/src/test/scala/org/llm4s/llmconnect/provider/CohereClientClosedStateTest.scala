package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for CohereClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class CohereClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: CohereConfig = CohereConfig(
    apiKey = "test-api-key-for-closed-state-testing",
    model = "command-r",
    baseUrl = "https://example.invalid",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "CohereClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new CohereClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("command-r")
  }

  it should "return ConfigurationError when streamComplete() is called (streaming not supported)" in {
    val client         = new CohereClient(createTestConfig)
    var chunksReceived = 0

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message.toLowerCase should include("stream")
    chunksReceived shouldBe 0
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new CohereClient(createTestConfig)

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
    val config = createTestConfig.copy(model = "command-r-plus")
    val client = new CohereClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("command-r-plus")
  }
}
