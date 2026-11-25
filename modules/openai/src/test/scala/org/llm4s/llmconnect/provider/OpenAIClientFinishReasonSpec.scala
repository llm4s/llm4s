package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.{ ContentFilteredError, NetworkError, RateLimitError, ServiceError }
import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, FinishReason, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, completion => completionOf, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * RFC 0009 Part 1 for `OpenAIClient`: `complete` reports the choice's `finish_reason` on the `Completion` (it read none
 * before), a stream's chunks carry it typed, and fake streaming passes the reply's own reason on instead of a made-up
 * `"stop"`. A `content_filter` finish is a `ContentFilteredError` on every path (PR 2). A native stream in which no
 * choice states a `finish_reason` is a `NetworkError`, checked by llm4s rather than left to the SDK, and an `error`
 * event fails it with the error it reports (PR 4).
 */
final class OpenAIClientFinishReasonSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def configFor(model: String) =
    OpenAIConfig
      .fromValues(
        modelName = ModelName(model),
        apiKey = Secret("test-api-key"),
        baseUrl = BaseUrl("https://example.invalid/v1")
      )
      .value

  private def reply(finish: String) = completionOf(
    s"""{"id":"chatcmpl-1","created":0,"model":"gpt-4o",
       |"choices":[{"index":0,"message":{"role":"assistant","content":"Hel"}$finish}]}""".stripMargin
  )

  private def complete(model: String, finish: String) =
    OpenAIClient
      .forTest(model, transport(complete = _ => reply(finish)), configFor(model))
      .complete(hi, CompletionOptions())

  "OpenAIClient.complete" should "report the choice's finish_reason, mapped" in {
    complete("gpt-4o", ""","finish_reason":"length"""").value.finishReason shouldBe FinishReason.Length
    complete("gpt-4o", ""","finish_reason":"stop"""").value.finishReason shouldBe FinishReason.Stop
  }

  it should "fail a content_filter finish with a ContentFilteredError, with the text it cut after and the usage" in {
    val error = complete(
      "gpt-4o",
      ""","finish_reason":"content_filter"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8},"x":[{"""
    ).left.value
    error shouldBe a[ContentFilteredError]
    val filtered = error.asInstanceOf[ContentFilteredError]
    filtered.provider shouldBe ProviderId("openai")
    filtered.reason shouldBe "content_filter"
    filtered.message should include("after 3 characters")
    filtered.usage.map(_.promptTokens) shouldBe Some(5)
  }

  it should "report Other(\"\") when the reply has no finish_reason" in {
    complete("gpt-4o", "").value.finishReason shouldBe FinishReason.Unknown
  }

  "OpenAIClient.streamComplete" should "carry the typed reason on the chunk and the Completion" in {
    val events = Seq(
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
      ),
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}"""
      )
    )
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = OpenAIClient
      .forTest("gpt-4o", transport(streaming = _ => stream(events*)), configFor("gpt-4o"))
      .streamComplete(hi, CompletionOptions())(chunks += _)
    chunks.flatMap(_.finishReason).toList shouldBe List(FinishReason.Length)
    result.value.finishReason shouldBe FinishReason.Length
  }

  it should "fail on a content_filter finish, keeping the text already delivered, with the usage sent after it" in {
    val events = Seq(
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
      ),
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}"""
      ),
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}"""
      )
    )
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = OpenAIClient
      .forTest("gpt-4o", transport(streaming = _ => stream(events*)), configFor("gpt-4o"))
      .streamComplete(hi, CompletionOptions())(chunks += _)
    chunks.flatMap(_.contentDelta).mkString shouldBe "Hel"
    chunks.flatMap(_.finishReason) shouldBe empty
    result.left.value match {
      case filtered: ContentFilteredError =>
        filtered.message should include("after 3 characters")
        filtered.usage.map(_.totalTokens) shouldBe Some(8)
      case other => fail(s"expected a ContentFilteredError, got $other")
    }
  }

  "fake streaming (o1)" should "fail a content_filter reply before delivering any chunk" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = OpenAIClient
      .forTest("o1", transport(complete = _ => reply(""","finish_reason":"content_filter"""")), configFor("o1"))
      .streamComplete(hi, CompletionOptions())(chunks += _)
    result.left.value shouldBe a[ContentFilteredError]
    chunks shouldBe empty
  }

  it should "pass the reply's own reason on, not a made-up stop" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = OpenAIClient
      .forTest("o1", transport(complete = _ => reply(""","finish_reason":"length"""")), configFor("o1"))
      .streamComplete(hi, CompletionOptions())(chunks += _)
    chunks.flatMap(_.finishReason).toList shouldBe List(FinishReason.Length)
    result.value.finishReason shouldBe FinishReason.Length
  }

  private def textEvent =
    """data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""

  /** A real SDK client, at a local server answering every stream with `body`, streaming once. */
  private def streamAt(body: String): (Either[org.llm4s.error.LLMError, ?], List[StreamedChunk]) = {
    val chunks = ListBuffer.empty[StreamedChunk]
    var result = Option.empty[Either[org.llm4s.error.LLMError, ?]]
    org.llm4s.testkit.LocalProviderTestServer.withServer("/")(
      org.llm4s.testkit.LocalProviderTestServer.sendSseResponse(_, body)
    ) { baseUrl =>
      val client = OpenAIConfig
        .fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(baseUrl), None)
        .flatMap(OpenAIClient(_))
        .value
      result = Some(client.streamComplete(hi, CompletionOptions())(chunks += _))
      client.close()
    }
    (result.get, chunks.toList)
  }

  "a native stream" should "fail with a NetworkError when no choice states a finish_reason, keeping the text" in {
    // the body ends with no reason and no [DONE]; then with [DONE] but no reason, which OpenAI never sends
    Seq(s"$textEvent\n\n", s"$textEvent\n\ndata: [DONE]\n\n").foreach { body =>
      val (result, chunks) = streamAt(body)
      result.left.value shouldBe a[NetworkError]
      result.left.value.message should include("finish_reason")
      chunks.flatMap(_.contentDelta).mkString shouldBe "Hel"
    }
  }

  it should "fail on an error event with the error it reports, keeping the text" in {
    val (result, chunks) =
      streamAt(s"""$textEvent\n\ndata: {"error":{"message":"The server had an error","type":"server_error"}}\n\n""")
    result.left.value match {
      case e: ServiceError =>
        e.status shouldBe 502
        e.message should include("The server had an error")
      case other => fail(s"expected a ServiceError, got $other")
    }
    chunks.flatMap(_.contentDelta).mkString shouldBe "Hel"
    // an HTTP failure status the error states keeps its meaning
    val limited = streamAt(s"""data: {"error":{"message":"slow down","code":429}}\n\n""")._1.left.value
    limited shouldBe a[RateLimitError]
    limited.message should include("slow down")
  }
}
