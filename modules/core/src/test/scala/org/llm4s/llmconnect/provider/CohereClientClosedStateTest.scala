package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }

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
    model = "command-r-plus",
    baseUrl = "https://example.invalid/v1",
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
    result.left.toOption.get.message should include("command-r-plus")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new CohereClient(createTestConfig)
    var chunksReceived = 0

    client.close()

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => { chunksReceived += 1; () }
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("command-r-plus")
    chunksReceived shouldBe 0
  }

  it should "allow multiple calls to close() without error" in {
    val client = new CohereClient(createTestConfig)

    client.close()
    client.close()
    client.close()

    // Verify that operations still fail with ConfigurationError after multiple closes
    val result = client.complete(createTestConversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "include model name in closed state error for complete()" in {
    val client = new CohereClient(createTestConfig)
    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    val error = result.left.toOption.get
    error.message should include("command-r-plus")
    error.message should include("closed")
  }

  it should "include model name in closed state error for streamComplete()" in {
    val client = new CohereClient(createTestConfig)
    client.close()

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => ()
    )

    result.isLeft shouldBe true
    val error = result.left.toOption.get
    error.message should include("command-r-plus")
    error.message should include("closed")
  }
}
