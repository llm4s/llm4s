package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for CohereClient closed state handling.
 */
class CohereClientClosedStateTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: CohereConfig =
    CohereConfig.fromValues(
      modelName = "command-r",
      apiKey = "test-api-key-for-closed-state-testing",
      baseUrl = "https://example.invalid"
    )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "CohereClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new CohereClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("closed")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new CohereClient(createTestConfig)
    var chunksReceived = 0

    client.close()

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("closed")
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
}
