package org.llm4s.llmconnect.provider

import org.scalatest.EitherValues
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import scala.collection.mutable.ListBuffer

final class OpenAIClientStreamingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  "OpenAIClient.streamComplete" should "safely handle null/empty choices and update tokens only when finished" in {
    val model = "gpt-4"

    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value

    val noChoices    = chunk("""{"id":"chatcmpl-1","created":0,"choices":null}""")
    val emptyChoices = chunk("""{"id":"chatcmpl-1","created":0,"choices":[]}""")
    val contentChunk = chunk(
      """{
        |"id":"chatcmpl-1",
        |"created":0,
        |"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]
        |}""".stripMargin
    )
    val stopChunkWithUsage = chunk(
      """{
        |"id":"chatcmpl-1",
        |"created":0,
        |"choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant"}}],
        |"usage":{"completion_tokens":5,"prompt_tokens":10,"total_tokens":15}
        |}""".stripMargin
    )

    val chunks = stream(noChoices, emptyChoices, contentChunk, stopChunkWithUsage)
    val client = OpenAIClient.forTest(model, transport(streaming = _ => chunks), config)

    val conversation = Conversation(Seq(UserMessage("hello")))
    val received     = scala.collection.mutable.ListBuffer.empty[String]

    val result = client.streamComplete(conversation, CompletionOptions(), c => received += c.content.getOrElse(""))

    result.isRight shouldBe true

    val completion = result.toOption.get
    completion.id shouldBe "chatcmpl-1"
    completion.model shouldBe model
    completion.content shouldBe "Hi"
    completion.usage.map(_.promptTokens) shouldBe Some(10)
    completion.usage.map(_.completionTokens) shouldBe Some(5)

    // Only the real content chunk contributes non-empty content.
    received.toList should contain("Hi")
    // The SDK stream holds the HTTP response open; it is closed once read.
    chunks.closed shouldBe true
  }

  it should "record provider exchanges for native streaming when logging is enabled" in {
    val model = "gpt-4"
    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value
    val contentChunk = chunk(
      """{
        |"id":"chatcmpl-stream-1",
        |"created":0,
        |"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]
        |}""".stripMargin
    )
    val stopChunk = chunk(
      """{
        |"id":"chatcmpl-stream-1",
        |"created":0,
        |"choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant"}}],
        |"usage":{"completion_tokens":3,"prompt_tokens":7,"total_tokens":10}
        |}""".stripMargin
    )
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange

    val client = OpenAIClient.forTest(
      model,
      transport(streaming = _ => stream(contentChunk, stopChunk)),
      config,
      exchangeLogging = ProviderExchangeLogging.enabled(sink)
    )

    val result = client.streamComplete(Conversation(Seq(UserMessage("hello"))), CompletionOptions(), _ => ())

    result.isRight shouldBe true
    recorded should have size 1
    recorded.head.provider shouldBe "openai"
    recorded.head.requestBody should include("messages")
    recorded.head.requestBody should include("hello")
    recorded.head.responseBody.value should include("chatcmpl-stream-1")
    recorded.head.responseBody.value should include("Hello")
    recorded.head.errorMessage shouldBe empty
  }

  it should "record a failed provider exchange when native streaming throws" in {
    val model = "gpt-4"
    val config = OpenAIConfig
      .fromValues(
        modelName = model,
        apiKey = "test-api-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .value
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit =
        recorded += exchange

    val client = OpenAIClient.forTest(
      model,
      transport(streaming = _ => throw new RuntimeException("stream connection failed")),
      config,
      exchangeLogging = ProviderExchangeLogging.enabled(sink)
    )

    val result = client.streamComplete(Conversation(Seq(UserMessage("hello"))), CompletionOptions(), _ => ())

    result.isLeft shouldBe true
    recorded should have size 1
    recorded.head.provider shouldBe "openai"
    recorded.head.responseBody shouldBe empty
    recorded.head.errorMessage.value should include("stream connection failed")
  }
}
