package org.llm4s.llmconnect.provider

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.{ ContentFilteredError, LLMError, NetworkError, RateLimitError }
import org.llm4s.llmconnect.{ LlmClientOptions, ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIApi, OpenAIConfig }
import org.llm4s.llmconnect.model.{
  Completion,
  CompletionOptions,
  Conversation,
  FinishReason,
  RequestAdjustment,
  RequestOption,
  StreamedChunk,
  UserMessage
}
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer
import scala.util.Using

/** `OpenAIClient.streamComplete` on the Responses API over the real `openai-java` transport, against a local server. */
final class OpenAIResponsesStreamingSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:
  import OpenAIResponsesFixtures.{ message => outputMessage, * }

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hello = Conversation(Seq(UserMessage("hello")))

  final private case class Seen(path: String, body: ujson.Value)

  /** One `streamComplete` call; the chunks delivered, the result, the exchange log and the request. */
  final private case class Streamed(
    result: Either[LLMError, Completion],
    chunks: List[StreamedChunk],
    exchanges: List[ProviderExchange],
    seen: Option[Seen]
  ):
    def request: Seen          = seen.getOrElse(fail("no request reached the server"))
    def completion: Completion = result.value
    def toolDeltas             = chunks.flatMap(_.toolCallDelta)
    def accumulated: Completion =
      val accumulator = StreamingAccumulator(ModelName("gpt-4o"))
      chunks.foreach(accumulator.addChunk)
      accumulator.toCompletion.value

  private def configFor(baseUrl: String, model: String): OpenAIConfig =
    OpenAIConfig.fromValues(ModelName(model), Secret("sk-test"), BaseUrl(s"$baseUrl/v1")).value

  /** Serves `body` as an event stream (or as JSON with `status`, when that is not 200) to one `streamComplete`. */
  private def streamed(
    body: String,
    model: String = "gpt-4o",
    options: CompletionOptions = CompletionOptions(),
    status: Int = 200,
    json: Boolean = false,
    onChunk: Option[StreamedChunk => Unit] = None,
    keepReasoning: Boolean = true
  ): Streamed =
    val seen      = new AtomicReference[Seen]()
    val chunks    = ListBuffer.empty[StreamedChunk]
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = exchanges += exchange
    var out = Option.empty[Either[LLMError, Completion]]
    LocalProviderTestServer.withServer("/") { (exchange: HttpExchange) =>
      val sent = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      seen.set(Seen(exchange.getRequestURI.getPath, ujson.read(sent)))
      if json || status != 200 then LocalProviderTestServer.sendJsonResponse(exchange, status, body)
      else LocalProviderTestServer.sendSseResponse(exchange, body)
    } { baseUrl =>
      val client = OpenAIClient(
        configFor(baseUrl, model).withApi(OpenAIApi.Responses).withKeepReasoning(keepReasoning),
        LlmClientOptions().withExchangeLogging(ProviderExchangeLogging.Enabled(sink))
      ).value
      out = Some(client.streamComplete(hello, options)(onChunk.getOrElse(chunks += _)))
      client.close()
    }
    Streamed(out.get, chunks.toList, exchanges.toList, Option(seen.get()))

  private val throwing: Option[StreamedChunk => Unit] = Some(_ => throw new IllegalStateException("consumer failed"))

  /** Runs `body` with the client's logger off, for a test whose failure the client logs with its stack trace. */
  private def quietly[A](body: => A): A =
    val logger   = org.slf4j.LoggerFactory.getLogger(classOf[OpenAIClient]).asInstanceOf[LBLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.OFF)
    Using.resource(new AutoCloseable { def close(): Unit = logger.setLevel(previous) })(_ => body)

  /** The same reply served unstreamed to `complete`. */
  private def unstreamed(body: String, model: String): Completion =
    var out = Option.empty[Either[LLMError, Completion]]
    LocalProviderTestServer.withServer("/") { exchange =>
      LocalProviderTestServer.sendJsonResponse(exchange, 200, body)
    } { baseUrl =>
      val client = OpenAIClient(configFor(baseUrl, model).withApi(OpenAIApi.Responses)).value
      out = Some(client.complete(hello, CompletionOptions()))
      client.close()
    }
    out.get.value

  "streamComplete on the Responses API" should "post a streaming request and deliver text as it arrives" in {
    val s = streamed(
      sse(created(), textDelta("The answer"), textDelta(" is 4."), completed(reply(outputMessage("The answer is 4."))))
    )
    s.request.path shouldBe "/v1/responses"
    s.request.body("stream").bool shouldBe true
    s.request.body("store").bool shouldBe false
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("The answer", " is 4.")
    s.chunks.last.finishReason shouldBe Some(FinishReason.Stop)
    s.chunks.init.flatMap(_.finishReason) shouldBe empty
    s.completion.content shouldBe "The answer is 4."
    s.completion.usage.map(_.totalTokens) shouldBe Some(15)
  }

  it should "deliver a tool call's name and arguments with its call id on every delta" in {
    val s = streamed(
      sse(
        created(),
        callAdded("call_1", "get_weather", "fc_1", 0),
        argumentsDelta("{\"city\":", "fc_1", 0),
        argumentsDelta("\"Paris\"}", "fc_1", 0),
        completed(reply(functionCall("call_1", "get_weather", """{"city":"Paris"}""")))
      )
    )
    s.toolDeltas should have size 3
    s.toolDeltas.map(_.id.asString).distinct shouldBe Seq("call_1")
    s.toolDeltas.head.name shouldBe "get_weather"
    s.completion.toolCalls.map(_.name) shouldBe Seq("get_weather")
    s.chunks.last.finishReason shouldBe Some(FinishReason.ToolCalls)
    val rebuilt = s.accumulated.toolCalls
    rebuilt.map(c => (c.id.asString, c.name)) shouldBe Seq("call_1" -> "get_weather")
    rebuilt.head.arguments shouldBe ujson.Obj("city" -> "Paris")
  }

  it should "keep two interleaved tool calls apart" in {
    val s = streamed(
      sse(
        callAdded("call_1", "get_weather", "fc_1", 0),
        callAdded("call_2", "get_time", "fc_2", 1),
        argumentsDelta("{\"city\":", "fc_1", 0),
        argumentsDelta("{\"zone\":", "fc_2", 1),
        argumentsDelta("\"Paris\"}", "fc_1", 0),
        argumentsDelta("\"CET\"}", "fc_2", 1),
        completed(
          reply(
            functionCall("call_1", "get_weather", """{"city":"Paris"}""") + "," +
              functionCall("call_2", "get_time", """{"zone":"CET"}""")
          )
        )
      )
    )
    s.accumulated.toolCalls.map(c => (c.id.asString, c.name, c.arguments)) should contain theSameElementsAs Seq(
      ("call_1", "get_weather", ujson.Obj("city" -> "Paris")),
      ("call_2", "get_time", ujson.Obj("zone" -> "CET"))
    )
    s.completion.toolCalls.map(_.id.asString) shouldBe Seq("call_1", "call_2")
  }

  it should "deliver reasoning summary text as thinking deltas and keep the reasoning on the final message" in {
    val s = streamed(
      sse(
        summaryDelta("Thinking"),
        summaryDelta(" it through."),
        textDelta("4"),
        completed(reply(reasoningItem("rs_1", summary = Seq("Thinking it through.")) + "," + outputMessage("4")))
      ),
      model = "gpt-6.1-terra"
    )
    s.chunks.flatMap(_.thinkingDelta) shouldBe Seq("Thinking", " it through.")
    s.completion.thinking shouldBe Some("Thinking it through.")
    s.completion.message.hasSealedThinking shouldBe true
  }

  it should "keep no reasoning on the final message when keepReasoning is false, and still deliver the summary" in {
    val s = streamed(
      sse(
        summaryDelta("Thinking"),
        summaryDelta(" it through."),
        textDelta("4"),
        completed(reply(reasoningItem("rs_1", summary = Seq("Thinking it through.")) + "," + outputMessage("4")))
      ),
      model = "gpt-6.1-terra",
      keepReasoning = false
    )
    s.chunks.flatMap(_.thinkingDelta) shouldBe Seq("Thinking", " it through.")
    s.request.body.obj.keySet should not contain "include"
    s.completion.thinking shouldBe Some("Thinking it through.")
    s.completion.message.hasSealedThinking shouldBe false
    s.completion.message.thinkingBinding shouldBe None
  }

  it should "separate a reasoning item's summary parts as the final thinking does" in {
    val s = streamed(
      sse(
        summaryPartAdded(0),
        summaryDelta("First."),
        summaryPartAdded(1),
        summaryDelta("Second."),
        textDelta("4"),
        completed(reply(reasoningItem("rs_1", summary = Seq("First.", "Second.")) + "," + outputMessage("4")))
      ),
      model = "gpt-6.1-terra"
    )
    s.chunks.flatMap(_.thinkingDelta) shouldBe Seq("First.", "\n\n", "Second.")
    s.chunks.flatMap(_.thinkingDelta).mkString shouldBe s.completion.thinking.value
  }

  it should "emit no separator for a single summary part, or between two reasoning items" in {
    val one = streamed(
      sse(
        summaryPartAdded(0),
        summaryDelta("Only."),
        completed(reply(reasoningItem("rs_1", summary = Seq("Only.")) + "," + outputMessage("4")))
      ),
      model = "gpt-6.1-terra"
    )
    one.chunks.flatMap(_.thinkingDelta) shouldBe Seq("Only.")

    val two = streamed(
      sse(
        summaryPartAdded(0, "rs_1"),
        summaryDelta("First.", "rs_1"),
        summaryPartAdded(0, "rs_2"),
        summaryDelta("Second.", "rs_2"),
        completed(
          reply(
            reasoningItem("rs_1", summary = Seq("First.")) + "," + reasoningItem("rs_2", summary = Seq("Second.")) +
              "," + outputMessage("4")
          )
        )
      ),
      model = "gpt-6.1-terra"
    )
    two.chunks.flatMap(_.thinkingDelta).mkString shouldBe two.completion.thinking.value
  }

  it should "return the same message complete returns for the same reply" in {
    val body = reply(
      reasoningItem("rs_1", summary = Seq("Thinking.")) + "," + outputMessage("Let me check.") + "," +
        functionCall("call_1", "get_weather", """{"city":"Paris"}""")
    )
    val s = streamed(
      sse(
        created(),
        summaryDelta("Thinking."),
        textDelta("Let me check."),
        callAdded("call_1", "get_weather", "fc_call_1", 2),
        argumentsDelta("""{"city":"Paris"}""", "fc_call_1", 2),
        completed(body)
      ),
      model = "gpt-6.1-terra"
    )
    val streamedCompletion = s.completion
    val plain              = unstreamed(body, "gpt-6.1-terra")
    streamedCompletion.message.hasSealedThinking shouldBe true
    streamedCompletion.message.thinkingBinding shouldBe defined
    streamedCompletion.message shouldBe plain.message
    streamedCompletion.usage shouldBe plain.usage
    streamedCompletion.finishReason shouldBe plain.finishReason
  }

  it should "deliver a refusal's text as content" in {
    val s = streamed(sse(refusalDelta("I can't"), refusalDelta(" help."), completed(reply(refusal("I can't help.")))))
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("I can't", " help.")
    s.completion.content shouldBe "I can't help."
  }

  it should "ignore events it does not map" in {
    val s = streamed(
      sse(
        textDelta("a"),
        """{"type":"response.web_search_call.searching","item_id":"ws_1","output_index":1}""",
        """{"type":"response.not_a_real_event","anything":[1,2]}""",
        textDelta("b"),
        completed(reply(outputMessage("ab")))
      )
    )
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("a", "b")
    s.completion.content shouldBe "ab"
  }

  it should "end Length for a reply cut at max_output_tokens, with the text delivered" in {
    val s = streamed(
      sse(
        textDelta("partial"),
        incomplete(
          reply(
            outputMessage("partial"),
            status = "incomplete",
            extra = """"incomplete_details":{"reason":"max_output_tokens"}"""
          )
        )
      )
    )
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("partial")
    s.chunks.last.finishReason shouldBe Some(FinishReason.Length)
    s.completion.finishReason shouldBe FinishReason.Length
  }

  it should "fail with a ContentFilteredError for a reply stopped by the content filter, with no finish chunk" in {
    val s = streamed(
      sse(
        textDelta("cut"),
        incomplete(
          reply(
            outputMessage("cut"),
            status = "incomplete",
            extra = """"incomplete_details":{"reason":"content_filter"}"""
          )
        )
      )
    )
    s.result.left.value shouldBe a[ContentFilteredError]
    s.chunks.flatMap(_.finishReason) shouldBe empty
  }

  it should "fail with the mapped error on response.failed, keeping the text already delivered" in {
    val s = streamed(
      sse(
        textDelta("so far"),
        failed(reply("", status = "failed", extra = """"error":{"code":"rate_limit_exceeded","message":"slow down"}"""))
      )
    )
    s.result.left.value shouldBe a[RateLimitError]
    s.result.left.value.message should include("slow down")
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
    s.chunks.flatMap(_.finishReason) shouldBe empty
  }

  it should "fail a response.failed with invalid_prompt as the same non-retryable 400-class error" in {
    val s = streamed(
      sse(failed(reply("", status = "failed", extra = """"error":{"code":"invalid_prompt","message":"bad prompt"}""")))
    )
    s.result.left.value shouldBe a[org.llm4s.error.ValidationError]
    s.result.left.value.isRetryable shouldBe false
  }

  it should "fail a response.failed with bio_policy as a ContentFilteredError, keeping the text delivered" in {
    val s = streamed(
      sse(
        textDelta("so far"),
        failed(reply("", status = "failed", extra = """"error":{"code":"bio_policy","message":"refused"}"""))
      )
    )
    s.result.left.value shouldBe a[ContentFilteredError]
    s.result.left.value.isRetryable shouldBe false
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
    s.chunks.flatMap(_.finishReason) shouldBe empty
  }

  it should "attach the reply's usage to a response.failed policy refusal, and none when it reports none" in {
    val body = reply("", status = "failed", extra = """"error":{"code":"bio_policy","message":"refused"}""")
    val s    = streamed(sse(failed(body)))
    s.result.left.value.asInstanceOf[ContentFilteredError].usage.map(_.totalTokens) shouldBe Some(15)

    val json = ujson.read(body)
    json.obj.remove("usage")
    val bare = streamed(sse(failed(json.render())))
    bare.result.left.value.asInstanceOf[ContentFilteredError].usage shouldBe None
  }

  it should "fail with the mapped error on an error event" in {
    val s = streamed(sse(textDelta("so far"), errorEvent("server_error", "boom")))
    s.result.left.value shouldBe a[org.llm4s.error.ServiceError]
    s.result.left.value.isRetryable shouldBe true
    s.result.left.value.message should include("boom")
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
    s.chunks.flatMap(_.finishReason) shouldBe empty
  }

  it should "map a rate_limit_exceeded error event to a RateLimitError, as a failed reply's" in {
    val s = streamed(sse(errorEvent("rate_limit_exceeded", "slow down")))
    s.result.left.value shouldBe a[RateLimitError]
  }

  private def nestedError(code: String): Streamed =
    val s = streamed(sse(textDelta("so far"), nestedErrorEvent(code, "boom")))
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
    s.chunks.flatMap(_.finishReason) shouldBe empty
    s.exchanges should have size 1
    s

  it should "map a nested rate_limit_exceeded error event to a RateLimitError" in {
    nestedError("rate_limit_exceeded").result.left.value shouldBe a[RateLimitError]
  }

  it should "map a nested bio_policy error event to a non-retryable ContentFilteredError" in {
    val error = nestedError("bio_policy").result.left.value
    error shouldBe a[ContentFilteredError]
    error.isRetryable shouldBe false
  }

  it should "map a nested invalid_prompt error event to a non-retryable ValidationError" in {
    val error = nestedError("invalid_prompt").result.left.value
    error shouldBe a[org.llm4s.error.ValidationError]
    error.isRetryable shouldBe false
  }

  it should "map a nested server_error error event to a retryable ServiceError" in {
    val error = nestedError("server_error").result.left.value
    error shouldBe a[org.llm4s.error.ServiceError]
    error.isRetryable shouldBe true
  }

  it should "fail as unfinished when the stream ends with no terminal event" in {
    val s = streamed(sse(created(), textDelta("so far")))
    s.result.left.value shouldBe a[NetworkError]
    s.chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
    s.chunks.flatMap(_.finishReason) shouldBe empty
  }

  it should "return a Left when the caller's onChunk throws, and record the exchange" in {
    val s = quietly(streamed(sse(textDelta("a"), completed(reply(outputMessage("a")))), onChunk = throwing))
    s.result.isLeft shouldBe true
    s.exchanges should have size 1
    s.exchanges.head.errorMessage shouldBe defined
  }

  it should "return a Left when the caller's onChunk throws on the finish chunk alone" in {
    val s = quietly(
      streamed(
        sse(completed(reply(outputMessage("")))),
        onChunk = Some(c => if c.finishReason.isDefined then throw new IllegalStateException("consumer failed"))
      )
    )
    s.result.isLeft shouldBe true
    s.exchanges should have size 1
  }

  it should "record one exchange holding the request and the events received, on success and on failure" in {
    val ok = streamed(sse(textDelta("hi"), completed(reply(outputMessage("hi")))))
    ok.exchanges should have size 1
    ok.exchanges.head.requestBody should include("\"stream\":true")
    ok.exchanges.head.requestBody should include("store")
    ok.exchanges.head.responseBody.getOrElse("") should include("response.completed")

    val unfinished = streamed(sse(textDelta("so far")))
    unfinished.exchanges should have size 1
    unfinished.exchanges.head.errorMessage shouldBe defined
    unfinished.exchanges.head.responseBody.getOrElse("") should include("so far")

    val refused = streamed("""{"error":{"message":"boom","type":"server_error"}}""", status = 500)
    refused.result.isLeft shouldBe true
    refused.chunks shouldBe empty
    refused.exchanges should have size 1
    refused.exchanges.head.errorMessage shouldBe defined
    refused.exchanges.head.responseBody shouldBe None
  }

  it should "report the same adjustments and estimated cost as complete" in {
    val s = streamed(
      sse(textDelta("hi"), completed(reply(outputMessage("hi")))),
      options = CompletionOptions().withPresencePenalty(0.5).withFrequencyPenalty(0.25)
    )
    s.completion.adjustments.collect { case RequestAdjustment.Dropped(option, _) =>
      option
    } should contain theSameElementsAs Seq(RequestOption.PresencePenalty, RequestOption.FrequencyPenalty)
    s.completion.estimatedCost shouldBe defined
    s.request.body.obj.keySet should not contain "presence_penalty"
  }

  it should "still answer a model that cannot stream natively with one unstreamed call" in {
    val s = streamed(reply(outputMessage("hi")), model = "o1", json = true)
    s.request.path shouldBe "/v1/responses"
    s.request.body.obj.keySet should not contain "stream"
    s.chunks.flatMap(_.contentDelta).mkString shouldBe "hi"
    s.chunks.last.finishReason shouldBe Some(FinishReason.Stop)
    s.completion.content shouldBe "hi"
    s.exchanges should have size 1
  }

  it should "deliver an unstreamed reply's tool calls through the chunks, for a model that cannot stream natively" in {
    val s = streamed(reply(functionCall("call_1", "get_weather", """{"city":"Paris"}""")), model = "o1", json = true)
    s.completion.toolCalls.map(_.id.asString) shouldBe Seq("call_1")
    s.toolDeltas.map(c => (c.id.asString, c.name, c.arguments.render())) shouldBe
      Seq(("call_1", "get_weather", """{"city":"Paris"}"""))
    s.chunks.last.finishReason shouldBe Some(FinishReason.ToolCalls)
  }

  it should "return a Left when the caller's onChunk throws, for a model that cannot stream natively" in {
    val s = quietly(streamed(reply(outputMessage("hi")), model = "o1", json = true, onChunk = throwing))
    s.result.isLeft shouldBe true
    s.exchanges should have size 1
    // the reply was read, so the exchange keeps both bodies
    s.exchanges.head.requestBody should not be empty
    s.exchanges.head.responseBody shouldBe defined
  }
