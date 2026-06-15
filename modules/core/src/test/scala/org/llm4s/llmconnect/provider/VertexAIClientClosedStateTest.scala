package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for [[VertexAIClient]] closed-state handling.
 *
 * Verifies that:
 *  - Operations fail with [[ConfigurationError]] after `close()` is called.
 *  - `close()` is idempotent (can be called multiple times safely).
 */
class VertexAIClientClosedStateTest extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def testConfig: VertexAIConfig = VertexAIConfig(
    project = "test-project",
    location = "us-central1",
    model = "gemini-1.5-flash",
    accessToken = "test-token",
    baseUrl = "https://example.invalid/v1",
    contextWindow = 1048576,
    reserveCompletion = 8192,
  )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Hello")))

  "VertexAIClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new VertexAIClient(testConfig)
    client.close()

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new VertexAIClient(testConfig)
    var chunksReceived = 0
    client.close()

    val result = client.streamComplete(conversation, CompletionOptions(), _ => chunksReceived += 1)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    chunksReceived shouldBe 0
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new VertexAIClient(testConfig)

    noException should be thrownBy {
      client.close()
      client.close()
      client.close()
    }

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "include the model name in the closed error message" in {
    val config = testConfig.copy(model = "gemini-2.0-flash")
    val client = new VertexAIClient(config)
    client.close()

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("gemini-2.0-flash")
  }
}
