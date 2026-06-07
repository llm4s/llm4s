package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import com.azure.ai.openai.models.{ ChatCompletions, ChatCompletionsOptions }
import com.azure.core.util.IterableStream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ Conversation, CompletionOptions, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.slf4j.LoggerFactory

/**
 * Tests for OpenAIClient closed state handling.
 *
 * These tests verify that:
 * - Operations fail with ConfigurationError after close() is called
 * - close() is idempotent (can be called multiple times safely)
 */
class OpenAIClientClosedStateTest extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def createTestConfig: OpenAIConfig = OpenAIConfig.fromValues(
    modelName = "gpt-4",
    apiKey = "test-api-key-for-closed-state-testing",
    organization = None,
    // Must never be used by unit tests (no network). We keep a clearly fake endpoint.
    baseUrl = "https://example.invalid/v1"
  )

  private def createTestConversation: Conversation =
    Conversation(Seq(UserMessage("Hello")))

  private def withOpenAIClientLoggerSilenced[A](body: => A): A = {
    val logger   = LoggerFactory.getLogger(classOf[OpenAIClient]).asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    try body
    finally logger.setLevel(previous)
  }

  final private class StubTransport extends OpenAIClientTransport {
    var completeCalls       = 0
    var streamCompleteCalls = 0

    override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions = {
      completeCalls += 1
      throw new RuntimeException("stub transport invoked")
    }

    override def getChatCompletionsStream(
      model: String,
      options: ChatCompletionsOptions
    ): IterableStream[ChatCompletions] = {
      streamCompleteCalls += 1
      throw new RuntimeException("stub streaming transport invoked")
    }
  }

  "OpenAIClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new OpenAIClient(createTestConfig, org.llm4s.metrics.MetricsCollector.noop)

    // Close the client
    client.close()

    // Attempt to call complete()
    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("already closed")
    result.left.toOption.get.message should include("gpt-4")
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client         = new OpenAIClient(createTestConfig, org.llm4s.metrics.MetricsCollector.noop)
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
    val client = new OpenAIClient(createTestConfig, org.llm4s.metrics.MetricsCollector.noop)

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

  it should "succeed for operations before close() is called" in {
    val transport = new StubTransport
    val client = new OpenAIClient(
      createTestConfig.model,
      transport,
      createTestConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      org.llm4s.llmconnect.ProviderExchangeLogging.Disabled
    )

    // Before closing, complete() should reach the transport layer and fail there,
    // but NOT due to closed state.
    val result = withOpenAIClientLoggerSilenced {
      client.complete(createTestConversation, CompletionOptions())
    }

    transport.completeCalls shouldBe 1
    result.isLeft shouldBe true
    result.left.toOption.get match {
      case ce: ConfigurationError =>
        (ce.message should not).include("already closed")
      case _ =>
        // Other errors (like ServiceError from invalid API key) are expected
        succeed
    }
  }

  it should "include model name in the closed error message" in {
    val modelName = "gpt-4-turbo-preview"
    val config = OpenAIConfig.fromValues(
      modelName = modelName,
      apiKey = "test-api-key",
      organization = None,
      // Must never be used by unit tests (no network). We keep a clearly fake endpoint.
      baseUrl = "https://example.invalid/v1"
    )
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    client.close()

    val result = client.complete(createTestConversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include(modelName)
  }
}
