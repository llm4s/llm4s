package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.FireworksConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for FireworksClient closed state handling.
 *
 * Verifies that operations fail with ConfigurationError after close() and that
 * close() is idempotent.
 */
class FireworksClientClosedStateTest extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def createTestConfig: FireworksConfig = FireworksConfig(
    apiKey = "fw-test-key-closed-state",
    model = "accounts/fireworks/models/llama-v3p1-8b-instruct",
    baseUrl = "https://example.invalid",
    contextWindow = 131072,
    reserveCompletion = 4096
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "FireworksClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new FireworksClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message should include("already closed")
      },
      _ => fail("Expected Left(ConfigurationError)")
    )
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new FireworksClient(createTestConfig)
    var chunksReceived = 0

    client.close()

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.fold(
      err => err shouldBe a[ConfigurationError],
      _ => fail("Expected Left(ConfigurationError)")
    )
    chunksReceived shouldBe 0
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new FireworksClient(createTestConfig)

    noException should be thrownBy {
      client.close()
      client.close()
      client.close()
    }

    val result = client.complete(createTestConversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ConfigurationError],
      _ => fail("Expected Left(ConfigurationError) after close")
    )
  }

  it should "include model name in the closed error message" in {
    val config = createTestConfig.copy(model = "accounts/fireworks/models/firefunction-v2")
    val client = new FireworksClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.fold(
      err => err.message should include("accounts/fireworks/models/firefunction-v2"),
      _ => fail("Expected Left(ConfigurationError)")
    )
  }
}
