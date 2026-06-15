package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.PerplexityConfig
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for PerplexityClient closed state handling.
 *
 * Verifies that operations fail with ConfigurationError after close() is called
 * and that close() is idempotent.
 */
class PerplexityClientClosedStateTest extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def createTestConfig: PerplexityConfig = PerplexityConfig(
    apiKey = "test-api-key-for-closed-state-testing",
    model = "sonar",
    baseUrl = "https://example.invalid",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  "PerplexityClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new PerplexityClient(createTestConfig)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message should include("already closed")
        err.message should include("sonar")
      },
      _ => fail("Expected Left(ConfigurationError)")
    )
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new PerplexityClient(createTestConfig)
    var chunksReceived = 0

    client.close()

    val result = client.streamComplete(
      createTestConversation,
      CompletionOptions(),
      _ => chunksReceived += 1
    )

    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message should include("already closed")
      },
      _ => fail("Expected Left(ConfigurationError)")
    )
    chunksReceived shouldBe 0
  }

  it should "allow close() to be called multiple times (idempotent)" in {
    val client = new PerplexityClient(createTestConfig)

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
    val config = createTestConfig.copy(model = "sonar-pro")
    val client = new PerplexityClient(config)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.fold(
      err => err.message should include("sonar-pro"),
      _ => fail("Expected Left(ConfigurationError)")
    )
  }
}
