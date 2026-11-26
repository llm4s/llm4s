package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.{ LlmClientOptions, ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIApi, OpenAIConfig }
import org.llm4s.error.{
  ContentFilteredError,
  LLMError,
  ProcessingError,
  RateLimitError,
  ServiceError,
  UnsupportedContentPartError
}
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  FinishReason,
  ContentPart,
  Conversation,
  ReasoningEffort,
  RequestAdjustment,
  RequestOption,
  StreamedChunk,
  ResponseFormat,
  ServiceTier,
  SystemMessage,
  ThinkingBlock,
  ToolCall,
  ToolCallId,
  ToolMessage,
  UserMessage
}
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.collection.mutable.ListBuffer

/** `OpenAIClient` on the Responses API over the real `openai-java` transport, against a local HTTP server. */
final class OpenAIResponsesWireSpec extends AnyFlatSpec with Matchers with EitherValues:
  import OpenAIResponsesFixtures.{ functionCall, reasoningItem, refusal, reply, message => outputMessage }

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  final private case class Seen(method: String, path: String, authorization: Option[String], body: ujson.Value)

  private def capture(into: AtomicReference[Seen], exchange: HttpExchange): Unit =
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    into.set(
      Seen(
        exchange.getRequestMethod,
        exchange.getRequestURI.getPath,
        Option(exchange.getRequestHeaders.getFirst("Authorization")),
        ujson.read(body)
      )
    )

  private val hello = Conversation(Seq(UserMessage("hello")))

  private def configFor(baseUrl: String, model: String): OpenAIConfig =
    OpenAIConfig.fromValues(ModelName(model), Secret("sk-test"), BaseUrl(s"$baseUrl/v1")).value

  private def responsesClient(baseUrl: String, model: String = "gpt-4o"): OpenAIClient =
    OpenAIClient(configFor(baseUrl, model).withApi(OpenAIApi.Responses)).value

  "OpenAIClient on the Responses API" should "post to <baseUrl>/responses with store false and read the reply" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, reply(outputMessage("hi")))
    } { baseUrl =>
      val client = responsesClient(baseUrl)
      val result = client.complete(hello, CompletionOptions(maxTokens = Some(64)))
      client.close()

      val completion = result.value
      completion.content shouldBe "hi"
      completion.created shouldBe Instant.ofEpochSecond(1700000000L)
      completion.usage.map(_.promptTokens) shouldBe Some(9)
      completion.usage.map(_.completionTokens) shouldBe Some(4) // 6 output less 2 reasoning
      completion.usage.flatMap(_.thinkingTokens) shouldBe Some(2)
      completion.usage.map(_.totalTokens) shouldBe Some(15)

      val request = seen.get()
      request.method shouldBe "POST"
      request.path shouldBe "/v1/responses"
      request.authorization shouldBe Some("Bearer sk-test")
      request.body("model").str shouldBe "gpt-4o"
      request.body("store").bool shouldBe false
      request.body("max_output_tokens").num shouldBe 64
      request.body("input")(0)("role").str shouldBe "user"
      request.body("input")(0)("content").str shouldBe "hello"
      request.body.obj.keySet should contain noneOf ("messages", "max_tokens", "previous_response_id", "include")
    }
  }

  it should "post to /chat/completions when api is unset or chat-completions" in {
    List(None, Some(OpenAIApi.ChatCompletions)).foreach { api =>
      val seen = new AtomicReference[Seen]()
      LocalProviderTestServer.withServer("/") { exchange =>
        capture(seen, exchange)
        LocalProviderTestServer.sendJsonResponse(
          exchange,
          200,
          LocalProviderTestServer.openAICompletion("hi", "gpt-4o")
        )
      } { baseUrl =>
        val client = OpenAIClient(configFor(baseUrl, "gpt-4o").withApi(api)).value
        val result = client.complete(hello, CompletionOptions())
        client.close()

        result.value.content shouldBe "hi"
        seen.get().path shouldBe "/v1/chat/completions"
      }
    }
  }

  it should "report a 404 from a gateway without /responses as the provider's service error" in {
    val requests = new AtomicInteger(0)
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      requests.incrementAndGet(): Unit
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        404,
        """{"error":{"message":"Not found","type":"invalid_request_error"}}"""
      )
    } { baseUrl =>
      val client = responsesClient(baseUrl)
      val error  = client.complete(hello, CompletionOptions()).left.value
      client.close()

      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].status shouldBe 404
      requests.get() shouldBe 1
    }
  }

  // ---- the whole request ----

  final private case class Sent(result: Either[LLMError, org.llm4s.llmconnect.model.Completion], seen: Option[Seen]):
    def body: ujson.Value                                 = seen.getOrElse(fail("no request reached the server")).body
    def completion: org.llm4s.llmconnect.model.Completion = result.value

  private def send(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    model: String = "gpt-4o"
  ): Sent =
    val seen = new AtomicReference[Seen]()
    var out  = Option.empty[Sent]
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, reply(outputMessage("ok")))
    } { baseUrl =>
      val client = responsesClient(baseUrl, model)
      val result = client.complete(conversation, options)
      client.close()
      out = Some(Sent(result, Option(seen.get())))
    }
    out.get

  private lazy val bytes = IArray[Byte](1, 2, 3)

  private lazy val weatherTool =
    org.llm4s.toolapi
      .ToolBuilder[String](
        "get_weather",
        "Weather of a city",
        org.llm4s.toolapi.Schema.`object`[Map[String, Any]]("No parameters")
      )
      .withHandler(_ => Right("ok"))
      .build()
      .fold(e => fail(e.formatted), identity)

  it should "send a system message as an input message with role system, in its position" in {
    val sent = send(Conversation(Seq(UserMessage("a"), SystemMessage("be brief"), UserMessage("b"))))
    sent.body("input").arr.map(_("role").str).toSeq shouldBe Seq("user", "system", "user")
    sent.body("input")(1)("content").str shouldBe "be brief"
    sent.body.obj.keySet should not contain "instructions"
  }

  it should "send an image by URL and inline, and a PDF inline and by file id" in {
    val parts = Vector(
      ContentPart.Text("look"),
      ContentPart.image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png).value,
      ContentPart.image(MediaSource.Inline(bytes), MediaType.Png).value,
      ContentPart.document(MediaSource.Inline(bytes), MediaType.Pdf).value,
      ContentPart.document(MediaSource.ProviderFile("openai", "file-1"), MediaType.Pdf).value
    )
    val sent      = send(Conversation(Seq(UserMessage(parts))))
    val sentParts = sent.body("input")(0)("content").arr
    sentParts.map(_("type").str).toSeq shouldBe
      Seq("input_text", "input_image", "input_image", "input_file", "input_file")
    sentParts(0)("text").str shouldBe "look"
    sentParts(1)("image_url").str shouldBe "https://example.com/cat.png"
    sentParts(2)("image_url").str should startWith("data:image/png;base64,")
    sentParts(3)("file_data").str should startWith("data:application/pdf;base64,")
    sentParts(3)("filename").str shouldBe "document.pdf"
    sentParts(4)("file_id").str shouldBe "file-1"
  }

  it should "refuse a part it cannot send with an UnsupportedContentPartError, before any request" in {
    val pdfUrl = ContentPart.document(MediaSource.Url(URI.create("https://x.example/r.pdf")), MediaType.Pdf).value
    val sent   = send(Conversation(Seq(UserMessage(Vector(pdfUrl)))))
    sent.result.left.value shouldBe UnsupportedContentPartError(ProviderId("openai"), pdfUrl.kind, "user")
    sent.seen shouldBe None
  }

  private lazy val call1 = ToolCall(ToolCallId("call_1"), "get_weather", ujson.Obj("city" -> "Paris"))
  private lazy val call2 = ToolCall(ToolCallId("call_2"), "get_time", ujson.Obj("zone" -> "CET"))

  private def kind(item: ujson.Value): String = item.obj.get("type").map(_.str).getOrElse(item("role").str)

  it should "send an assistant turn as its text, then one function_call per tool call, and a tool result as function_call_output" in {
    val sent = send(
      Conversation(
        Seq(
          UserMessage("go"),
          AssistantMessage(Some("checking"), Seq(call1, call2)),
          ToolMessage("18C", ToolCallId("call_1")),
          ToolMessage("12:00", ToolCallId("call_2")),
          UserMessage("thanks")
        )
      )
    )
    val input = sent.body("input").arr
    input.map(kind).toSeq shouldBe
      Seq("user", "assistant", "function_call", "function_call", "function_call_output", "function_call_output", "user")
    input(1)("content").str shouldBe "checking"
    input(2)("call_id").str shouldBe "call_1"
    input(2)("name").str shouldBe "get_weather"
    input(2)("arguments").str shouldBe """{"city":"Paris"}"""
    input(2).obj.keySet should not contain "id"
    input(4)("call_id").str shouldBe "call_1"
    input(4)("output").str shouldBe "18C"
    input(5)("call_id").str shouldBe "call_2"
    input(5).obj.keySet should not contain "id"
  }

  it should "send an assistant turn with tool calls and no text as its function_calls alone" in {
    val sent = send(
      Conversation(
        Seq(UserMessage("go"), AssistantMessage(None, Seq(call1)), ToolMessage("18C", ToolCallId("call_1")))
      )
    )
    sent.body("input").arr.map(kind).toSeq shouldBe Seq("user", "function_call", "function_call_output")
  }

  it should "send nothing for an assistant turn with no text and no tool calls" in {
    val sent = send(Conversation(Seq(UserMessage("a"), AssistantMessage(None, Seq.empty), UserMessage("b"))))
    sent.result.isRight shouldBe true
    sent.body("input").arr.map(kind).toSeq shouldBe Seq("user", "user")
  }

  it should "never send an assistant turn's thinking, sealed or not" in {
    val thinking = Seq(ThinkingBlock.Text("hm", Some("sig")), ThinkingBlock.Opaque(ProviderId("openai"), "blob"))
    val sent =
      send(Conversation(Seq(UserMessage("a"), AssistantMessage(Some("hi"), Seq.empty, thinking), UserMessage("b"))))
    sent.body("input").arr.map(kind).toSeq shouldBe Seq("user", "assistant", "user")
    (sent.body("input").render() should not).include("reasoning")
  }

  it should "send tools in the flat function shape" in {
    val sent = send(hello, CompletionOptions().withTools(Seq(weatherTool)))
    val tool = sent.body("tools")(0)
    tool("type").str shouldBe "function"
    tool("name").str shouldBe "get_weather"
    tool("description").str shouldBe "Weather of a city"
    tool("strict").bool shouldBe true
    tool("parameters")("type").str shouldBe "object"
    tool.obj.keySet should not contain "function"
  }

  it should "send a JSON response format as text.format" in {
    send(hello, CompletionOptions().withResponseFormat(ResponseFormat.Json)).body("text")("format")("type").str shouldBe
      "json_object"

    val schema = ujson.Obj("type" -> "object", "properties" -> ujson.Obj("a" -> ujson.Obj("type" -> "string")))
    val format =
      send(hello, CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema, "answer", strict = false)))
        .body("text")("format")
    format("type").str shouldBe "json_schema"
    format("name").str shouldBe "answer"
    format("strict").bool shouldBe false
    format("schema")("properties")("a")("type").str shouldBe "string"
    format.obj.keySet should not contain "json_schema"
  }

  it should "send the reasoning effort as reasoning.effort, with tools, as asked" in {
    val sent = send(
      hello,
      CompletionOptions().withTools(Seq(weatherTool)).withReasoning(ReasoningEffort.Low),
      model = "gpt-6.1-terra"
    )
    sent.body("reasoning")("effort").str shouldBe "low"
    sent.completion.adjustments shouldBe empty
  }

  it should "send the service tier, and none when unset" in {
    send(hello, CompletionOptions().withServiceTier(ServiceTier.Flex)).body("service_tier").str shouldBe "flex"
    send(hello, CompletionOptions().withServiceTier(ServiceTier.Priority)).body("service_tier").str shouldBe "priority"
    send(hello).body.obj.keySet should not contain "service_tier"
  }

  it should "not send presence_penalty or frequency_penalty, and report each as Dropped when set" in {
    val sent =
      send(hello, CompletionOptions().withPresencePenalty(0.5).withFrequencyPenalty(0.25))
    sent.body.obj.keySet should not contain "presence_penalty"
    sent.body.obj.keySet should not contain "frequency_penalty"
    sent.completion.adjustments.collect { case RequestAdjustment.Dropped(option, _) =>
      option
    } should contain theSameElementsAs
      Seq(RequestOption.PresencePenalty, RequestOption.FrequencyPenalty)
    send(hello).completion.adjustments shouldBe empty
  }

  it should "send neither temperature nor top_p to a reasoning model that restricts sampling, and report them once" in {
    val options = CompletionOptions().withTemperature(0.3).withTopP(0.9).withPresencePenalty(0.5)
    val sent    = send(hello, options, model = "gpt-6.1-terra")
    sent.body.obj.keySet should not contain "temperature"
    sent.body.obj.keySet should not contain "top_p"
    sent.completion.adjustments.collect { case RequestAdjustment.Dropped(option, _) =>
      option
    } should contain theSameElementsAs
      Seq(RequestOption.Temperature, RequestOption.TopP, RequestOption.PresencePenalty)
  }

  it should "raise a max_output_tokens below 16 to 16 and report it, and send 16 as asked" in {
    val raised = send(hello, CompletionOptions(maxTokens = Some(15)))
    raised.body("max_output_tokens").num shouldBe 16
    raised.completion.adjustments.collect { case RequestAdjustment.Adjusted(option, asked, sent, _) =>
      (option, asked, sent)
    } shouldBe Seq((RequestOption.MaxTokens, "15", "16"))

    val exact = send(hello, CompletionOptions(maxTokens = Some(16)))
    exact.body("max_output_tokens").num shouldBe 16
    exact.completion.adjustments shouldBe empty
  }

  // ---- the whole reply ----

  private def answer(body: String): Either[LLMError, org.llm4s.llmconnect.model.Completion] =
    var out = Option.empty[Either[LLMError, org.llm4s.llmconnect.model.Completion]]
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(new AtomicReference[Seen](), exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, body)
    } { baseUrl =>
      val client = responsesClient(baseUrl)
      out = Some(client.complete(hello, CompletionOptions()))
      client.close()
    }
    out.get

  it should "read function_call items as tool calls, in output order" in {
    val completion = answer(
      reply(
        functionCall("call_1", "get_weather", """{"city":"Paris"}""") + "," +
          functionCall("call_2", "get_time", """{"zone":"CET"}""")
      )
    ).value
    completion.toolCalls.map(_.id.asString) shouldBe Seq("call_1", "call_2")
    completion.toolCalls.map(_.name) shouldBe Seq("get_weather", "get_time")
    completion.toolCalls.map(_.arguments.render()) shouldBe Seq("""{"city":"Paris"}""", """{"zone":"CET"}""")
    completion.finishReason shouldBe FinishReason.ToolCalls
  }

  it should "fail on function_call arguments that are not JSON as the Chat Completions path does" in {
    val error = answer(reply(functionCall("call_1", "get_weather", "{not json"))).left.value
    error shouldBe a[ProcessingError]
    error.message should include("malformed tool call 'get_weather'")
  }

  it should "ignore reasoning items and item types it does not map" in {
    val completion = answer(
      reply(
        Seq(
          reasoningItem("rs_1"),
          """{"type":"web_search_call","id":"ws_1","status":"completed"}""",
          """{"type":"not_a_real_item"}""",
          outputMessage("hi")
        ).mkString(",")
      )
    ).value
    completion.content shouldBe "hi"
    completion.message.thinking shouldBe empty
    completion.finishReason shouldBe FinishReason.Stop
  }

  it should "deliver a refusal's text as the content" in {
    val completion = answer(reply(refusal("I can't help with that."))).value
    completion.content shouldBe "I can't help with that."
    completion.finishReason shouldBe FinishReason.Stop
  }

  it should "report an incomplete reply at max_output_tokens as Length, with the text so far" in {
    val completion = answer(
      reply(
        outputMessage("so far"),
        status = "incomplete",
        extra = """"incomplete_details":{"reason":"max_output_tokens"}"""
      )
    ).value
    completion.finishReason shouldBe FinishReason.Length
    completion.content shouldBe "so far"
  }

  it should "report an incomplete reply at the content filter as a ContentFilteredError carrying the usage" in {
    val error = answer(
      reply(outputMessage("cut"), status = "incomplete", extra = """"incomplete_details":{"reason":"content_filter"}""")
    ).left.value
    error shouldBe a[ContentFilteredError]
    error.asInstanceOf[ContentFilteredError].usage.map(_.totalTokens) shouldBe Some(15)
  }

  it should "report a failed reply as its mapped error" in {
    val error = answer(
      reply("", status = "failed", extra = """"error":{"code":"server_error","message":"boom"}""")
    ).left.value
    error shouldBe a[ServiceError]
    error.asInstanceOf[ServiceError].status shouldBe 502
    error.message should include("boom")
  }

  it should "report a failed reply with rate_limit_exceeded as a RateLimitError, keeping the message" in {
    val error = answer(
      reply("", status = "failed", extra = """"error":{"code":"rate_limit_exceeded","message":"slow down"}""")
    ).left.value
    error shouldBe a[RateLimitError]
    error.message should include("slow down")
  }

  it should "report a failed reply with no error object as the default message" in {
    val error = answer(reply("", status = "failed")).left.value
    error shouldBe a[ServiceError]
    error.message should include("the response failed")
  }

  it should "read url_citation annotations as citations" in {
    val item =
      """{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text",""" +
        """"text":"hi","annotations":[{"type":"url_citation","url":"https://example.com","title":"Example",""" +
        """"start_index":0,"end_index":2},{"type":"url_citation","url":"","title":"none","start_index":0,""" +
        """"end_index":1}]}]}"""
    val completion = answer(reply(item)).value
    completion.citations.map(_.url) shouldBe Seq("https://example.com")
    completion.citations.map(_.title) shouldBe Seq(Some("Example"))
    completion.citations.map(c => (c.startIndex, c.endIndex)) shouldBe Seq((Some(0), Some(2)))
  }

  it should "report the cached input tokens" in {
    val body = reply(outputMessage("hi")).replace(""""cached_tokens":0""", """"cached_tokens":5""")
    answer(body).value.usage.flatMap(_.cachedTokens) shouldBe Some(5)
  }

  it should "fall back to the configured model when the reply omits model" in {
    val body = ujson.read(reply(outputMessage("hi")))
    body.obj.remove("model")
    answer(body.render()).value.model shouldBe ModelName("gpt-4o")
  }

  it should "send a tool result of several text parts as the parts joined as ToolMessage.content joins them" in {
    val tool = ToolMessage(Seq(ContentPart.Text("a"), ContentPart.Text("b")), ToolCallId("call_1"))
    val sent = send(
      Conversation(Seq(UserMessage("go"), AssistantMessage(None, Seq(call1)), tool))
    )
    sent.body("input")(2)("output").str shouldBe tool.content
    sent.body("input")(2)("output").str should not be "ab"
  }

  /** One `streamComplete` call against a server answering `status` with `body`; the chunks, the result and the log. */
  final private case class Streamed(
    result: Either[LLMError, org.llm4s.llmconnect.model.Completion],
    chunks: List[StreamedChunk],
    exchanges: List[ProviderExchange],
    seen: Option[Seen]
  ):
    def request: Seen = seen.getOrElse(fail("no request reached the server"))

  private def streamed(
    body: String,
    status: Int = 200,
    options: CompletionOptions = CompletionOptions()
  ): Streamed =
    val seen      = new AtomicReference[Seen]()
    val chunks    = ListBuffer.empty[StreamedChunk]
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = exchanges += exchange
    var out = Option.empty[Either[LLMError, org.llm4s.llmconnect.model.Completion]]
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, status, body)
    } { baseUrl =>
      val client = OpenAIClient(
        configFor(baseUrl, "gpt-4o").withApi(OpenAIApi.Responses),
        LlmClientOptions().withExchangeLogging(ProviderExchangeLogging.Enabled(sink))
      ).value
      out = Some(client.streamComplete(hello, options)(chunks += _))
      client.close()
    }
    Streamed(out.get, chunks.toList, exchanges.toList, Option(seen.get()))

  it should "answer streamComplete with one unstreamed call, delivered as chunks" in {
    val s          = streamed(reply(outputMessage("hi")))
    val completion = s.result.value
    completion.content shouldBe "hi"
    s.chunks.flatMap(_.contentDelta).mkString shouldBe "hi"
    s.chunks.last.finishReason shouldBe Some(FinishReason.Stop)
    s.request.path shouldBe "/v1/responses"
    s.request.body.obj.keySet should not contain "stream"
  }

  it should "report the same adjustments on a streamed completion as complete does" in {
    val s = streamed(
      reply(outputMessage("hi")),
      options = CompletionOptions().withPresencePenalty(0.5).withFrequencyPenalty(0.25)
    )
    s.result.value.adjustments.collect { case RequestAdjustment.Dropped(option, _) =>
      option
    } should contain theSameElementsAs Seq(RequestOption.PresencePenalty, RequestOption.FrequencyPenalty)
  }

  it should "deliver a streamed reply's tool calls through the chunks" in {
    val s = streamed(reply(functionCall("call_1", "get_weather", """{"city":"Paris"}""")))
    s.result.value.toolCalls.map(_.id.asString) shouldBe Seq("call_1")
    val calls = s.chunks.flatMap(_.toolCallDelta)
    calls.map(_.id.asString) shouldBe Seq("call_1")
    calls.map(_.name) shouldBe Seq("get_weather")
    calls.map(_.arguments.render()) shouldBe Seq("""{"city":"Paris"}""")
    s.chunks.last.finishReason shouldBe Some(FinishReason.ToolCalls)
  }

  it should "record the Responses request and reply in the exchange log, once, and a 500 as one failed exchange" in {
    val ok = streamed(reply(outputMessage("hi")))
    ok.exchanges should have size 1
    ok.exchanges.head.requestBody should include("store")
    ok.exchanges.head.responseBody.getOrElse("") should include("resp_1")

    val failed = streamed("""{"error":{"message":"boom","type":"server_error"}}""", status = 500)
    failed.result.isLeft shouldBe true
    failed.chunks shouldBe empty
    failed.exchanges should have size 1
    failed.exchanges.head.errorMessage shouldBe defined
    failed.exchanges.head.responseBody shouldBe None
  }
