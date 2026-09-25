package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }
import org.llm4s.model.ModelRegistryService

/**
 * Tests for VertexAIClient closed state handling.
 *
 * Verifies that operations fail with ConfigurationError after close() is called,
 * and that close() is idempotent.
 */
class VertexAIClientClosedStateTest extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def createTestConfig: VertexAIConfig = VertexAIConfig(
    projectId = "test-project",
    location = "us-central1",
    model = "gemini-2.0-flash",
    credentialFilePath = None,
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "VertexAIClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new VertexAIClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("gemini-2.0-flash")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new VertexAIClient(createTestConfig)
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
    val client = new VertexAIClient(createTestConfig)

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
    val config = createTestConfig.copy(model = "gemini-1.5-pro")
    val client = new VertexAIClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("gemini-1.5-pro")
  }
}
