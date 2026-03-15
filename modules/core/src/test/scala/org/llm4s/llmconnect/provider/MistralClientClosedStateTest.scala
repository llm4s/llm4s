package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for MistralClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class MistralClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: MistralConfig = MistralConfig(
    apiKey = "test-api-key-for-closed-state-testing",
    model = "mistral-small-latest",
    baseUrl = "https://example.invalid",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "MistralClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new MistralClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message should include("already closed")
        err.message should include("mistral-small-latest")
      },
      _ => fail("Expected Left(ConfigurationError)")
    )
  }

  it should "return ConfigurationError when streamComplete() is called (streaming not supported)" in {
    val client         = new MistralClient(createTestConfig)
    var chunksReceived = 0

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message.toLowerCase should include("stream")
      },
      _ => fail("Expected Left(ConfigurationError)")
    )
    chunksReceived shouldBe 0
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new MistralClient(createTestConfig)

    noException should be thrownBy {
      client.close()
      client.close()
      client.close()
    }

    val result = client.complete(createTestConversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ConfigurationError],
      _ => fail("Expected Left(ConfigurationError)")
    )
  }

  it should "include model name in the closed error message" in {
    val config = createTestConfig.copy(model = "mistral-large-latest")
    val client = new MistralClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.fold(
      err => err.message should include("mistral-large-latest"),
      _ => fail("Expected Left(ConfigurationError)")
    )
  }
}
