package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, NetworkError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.config.{ OpenAICompatibleConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * `Right` from `streamComplete` means the model finished. A stream that ends with neither a
 * `finish_reason` nor `[DONE]` is a cut-off reply, and a mid-stream `error` event is a failure the
 * status code could not report, since it was already 200. Bedrock and watsonx hold the same rule.
 */
class OpenAICompatibleStreamEndingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val clientKey = "stream-ending-spec-key-8f3a"

  private def event(delta: String, finish: String = "null"): String =
    s"""data: {"id":"s","object":"chat.completion.chunk","created":1700000000,"model":"local-model",""" +
      s""""choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]}"""

  private val toolCallStart =
    """{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"purge_files","arguments":""}}]}"""

  private def sse(events: String*): String = events.map(_ + "\n\n").mkString

  /** The result of streaming `body`, and the chunks `onChunk` saw. */
  private def streaming[A](body: String)(check: (Result[Completion], List[StreamedChunk]) => A): Unit =
    withServer("/chat/completions")(sendSseResponse(_, body)) { baseUrl =>
      val config = OpenAICompatibleConfig(model = "local-model", baseUrl = baseUrl, apiKey = Some(Secret(clientKey)))
      val client = OpenAICompatibleClient(config).value
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), chunks += _)
      check(result, chunks.toList)
    }

  "streamComplete" should "fail when text stops with neither a finish_reason nor [DONE], after delivering the text" in
    streaming(sse(event("""{"content":"The answer is"}"""))) { (result, chunks) =>
      result.left.value shouldBe a[NetworkError]
      result.left.value.message should include("finish_reason")
      chunks.flatMap(_.content) shouldBe List("The answer is")
    }

  it should "fail, not report a call with empty arguments, when a tool call is cut before its arguments" in
    streaming(sse(event(toolCallStart)))((result, _) => result.left.value shouldBe a[NetworkError])

  it should "fail when a tool call is cut halfway through its arguments" in
    streaming(
      sse(
        event(toolCallStart),
        event("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"older_than_days\": 3"}}]}""")
      )
    )((result, _) => result.left.value shouldBe a[NetworkError])

  it should "fail on an empty body" in
    streaming("") { (result, chunks) =>
      result.left.value shouldBe a[NetworkError]
      chunks shouldBe Nil
    }

  it should "succeed on a finish_reason with no [DONE], which some servers never send" in
    streaming(sse(event("""{"content":"done"}""", "\"stop\"")))((result, _) => result.value.content shouldBe "done")

  it should "succeed on [DONE] with no finish_reason, which some servers never send" in
    streaming(sse(event("""{"content":"done"}"""), "data: [DONE]"))((result, _) => result.value.content shouldBe "done")

  it should "keep a tool call that finishes normally" in
    streaming(
      sse(
        event(toolCallStart),
        event("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"older_than_days\":3}"}}]}"""),
        event("{}", "\"tool_calls\""),
        "data: [DONE]"
      )
    ) { (result, _) =>
      result.value.toolCalls.map(c => c.name -> c.arguments) shouldBe
        Seq("purge_files" -> ujson.Obj("older_than_days" -> 3))
    }

  it should "fail on an error event, with its message, and read nothing after it" in
    streaming(
      sse(
        event("""{"content":"par"}"""),
        """data: {"error":{"message":"upstream overloaded","type":"server_error"}}""",
        event("""{"content":"tial"}""", "\"stop\""),
        "data: [DONE]"
      )
    ) { (result, chunks) =>
      result.left.value shouldBe a[ServiceError]
      result.left.value.message should include("upstream overloaded")
      chunks.flatMap(_.content) shouldBe List("par")
    }

  it should "fail on an error event whose error is a plain string" in
    streaming(sse("""data: {"error":"model unavailable"}""")) { (result, _) =>
      result.left.value.message should include("model unavailable")
    }

  it should "fail on an error event with no message it can read, with the event's text" in
    streaming(sse("""data: {"error":{"code":503}}""")) { (result, _) =>
      result.left.value shouldBe a[ServiceError]
      result.left.value.message should include("503")
    }

  it should "scrub the client's own key from an error event's message" in
    streaming(sse(s"""data: {"error":{"message":"bad key $clientKey"}}""")) { (result, _) =>
      (result.left.value.message should not).include(clientKey)
    }

  it should "return an error event at once, not wait for a server that holds the stream open after it" in
    withServer("/chat/completions") { exchange =>
      streamThenHold(exchange, """data: {"error":{"message":"overloaded"}}""" + "\n\n")
    } { baseUrl =>
      // Waiting for another line ends in this timeout's NetworkError instead of the event's ServiceError.
      val config = OpenAICompatibleConfig(model = "local-model", baseUrl = baseUrl, apiKey = Some(Secret(clientKey)))
        .withTimeouts(ProviderTimeouts.default.withStream(3.seconds))
      val result = OpenAICompatibleClient(config).value
        .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
      result.left.value shouldBe a[ServiceError]
      result.left.value.message should include("overloaded")
    }

  it should "not deliver the content of an event that also carries an error" in
    streaming(
      sse(
        """data: {"id":"s","error":{"message":"overloaded"},""" +
          """"choices":[{"index":0,"delta":{"content":"junk"},"finish_reason":"error"}]}"""
      )
    ) { (result, chunks) =>
      result.left.value shouldBe a[ServiceError]
      chunks shouldBe Nil
    }

  it should "not count an empty finish_reason as the model finishing" in
    streaming(sse(event("""{"content":"par"}""", "\"\"")))((result, _) => result.left.value shouldBe a[NetworkError])

  it should "fail on a finish_reason of error, which some servers send with no error field" in
    streaming(sse(event("""{"content":"par"}""", "\"error\""), "data: [DONE]")) { (result, _) =>
      result.left.value shouldBe a[ServiceError]
      result.left.value.message should include("error")
    }

  it should "keep the meaning of an error event's status code: a rejected key is not retried" in
    streaming(sse("""data: {"error":{"code":401,"message":"invalid key"}}""")) { (result, _) =>
      result.left.value shouldBe a[AuthenticationError]
      org.llm4s.reliability.RetryPolicy.isTransient(result.left.value) shouldBe false
    }

  it should "keep the meaning of an error event's status code: a rate limit stays one" in
    streaming(sse("""data: {"error":{"code":429,"message":"slow down"}}""")) { (result, _) =>
      result.left.value shouldBe a[RateLimitError]
    }

  it should "treat an error event's code that is not an HTTP failure status as a bad gateway" in
    streaming(sse("""data: {"error":{"code":"overloaded_error","message":"busy"}}""")) { (result, _) =>
      result.left.value shouldBe a[ServiceError]
      org.llm4s.reliability.RetryPolicy.isTransient(result.left.value) shouldBe true
    }

  it should "cut a very long error event message" in
    streaming(sse(s"""data: {"error":{"message":"${"x" * 5000}"}}""")) { (result, _) =>
      result.left.value.message.length should be < 2000
    }

  it should "report an error event as a retryable failure" in
    streaming(sse("""data: {"error":{"message":"overloaded"}}"""))((result, _) =>
      org.llm4s.reliability.RetryPolicy.isTransient(result.left.value) shouldBe true
    )

  for (empty <- List("null", "{}", "\"\""))
    it should s"not treat an empty error field ($empty) on a normal event as a failure" in
      streaming(
        sse(
          s"""data: {"id":"s","created":1700000000,"model":"local-model","error":$empty,""" +
            """"choices":[{"index":0,"delta":{"content":"fine"},"finish_reason":"stop"}]}""",
          "data: [DONE]"
        )
      )((result, _) => result.value.content shouldBe "fine")
}
