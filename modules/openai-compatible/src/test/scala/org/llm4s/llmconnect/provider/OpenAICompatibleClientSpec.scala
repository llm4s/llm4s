package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.{ AuthenticationError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * The shared `OpenAICompatibleClient`, driven through the generic `openai-compatible`
 * configuration: the standard dialect, optional authentication, configured headers, and the
 * behaviour the three per-provider clients used to disagree on (#1132) - the stream body
 * closed on every failure, and exactly one exchange recorded per call.
 */
class OpenAICompatibleClientSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def config(baseUrl: String, apiKey: Option[String] = None, headers: Map[String, String] = Map.empty) =
    OpenAICompatibleConfig(model = "local-model", baseUrl = baseUrl, apiKey = apiKey, headers = headers)

  private def client(cfg: OpenAICompatibleConfig, logging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled) =
    OpenAICompatibleClient(cfg, exchangeLogging = logging).value

  private def conversation = Conversation(Seq(UserMessage("hello")))

  private def headersOf(exchange: HttpExchange): Map[String, String] =
    exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap

  private def recordingSink(): (ListBuffer[ProviderExchange], ProviderExchangeLogging) = {
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = recorded += exchange
    (recorded, ProviderExchangeLogging.enabled(sink))
  }

  /** A port nothing listens on, so a request to it fails in transport. */
  private def deadBaseUrl: String = {
    val socket = new ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    s"http://localhost:$port"
  }

  /** A response body that remembers whether it was closed. */
  final private class TrackingBody(text: String) extends ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
    @volatile var closed = false
    override def close(): Unit = {
      closed = true
      super.close()
    }
  }

  private def streamingClient = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(config("http://localhost:1/v1")),
    OpenAICompatibleDialect.Standard
  )

  // ==========================================================================
  // Authentication and headers
  // ==========================================================================

  "OpenAICompatibleClient" should "send no Authorization header when the config has no API key" in {
    var seen = Map.empty[String, String]
    withServer("/chat/completions") { exchange =>
      seen = headersOf(exchange)
      sendJsonResponse(exchange, 200, openAICompletion("local reply", "local-model"))
    } { baseUrl =>
      client(config(baseUrl)).complete(conversation, CompletionOptions()).value.content shouldBe "local reply"
    }
    seen.keySet should not contain "authorization"
    seen("content-type") shouldBe "application/json"
  }

  it should "send the API key as a bearer token, and every configured header" in {
    var seen = Map.empty[String, String]
    withServer("/chat/completions") { exchange =>
      seen = headersOf(exchange)
      sendJsonResponse(exchange, 200, openAICompletion("ok", "local-model"))
    } { baseUrl =>
      val cfg = config(baseUrl, apiKey = Some("sk-local"), headers = Map("X-Team" -> "search", "X-Trace" -> "1"))
      client(cfg).complete(conversation, CompletionOptions()).isRight shouldBe true
    }
    seen("authorization") shouldBe "Bearer sk-local"
    seen("x-team") shouldBe "search"
    seen("x-trace") shouldBe "1"
  }

  it should "stream without an API key" in
    withServer("/chat/completions") { exchange =>
      headersOf(exchange).keySet should not contain "authorization"
      sendSseResponse(exchange, openAISseBody(Seq("Hello", " local"), "local-model"))
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client(config(baseUrl)).streamComplete(conversation, CompletionOptions(), chunks += _)
      result.value.content shouldBe "Hello local"
      result.value.model shouldBe "local-model"
      chunks.flatMap(_.content).mkString shouldBe "Hello local"
    }

  it should "report the configured context window and reserve" in {
    val c = client(OpenAICompatibleConfig("m", "http://localhost:1/v1", None, 32768, 4096))
    (c.getContextWindow(), c.getReserveCompletion()) shouldBe (32768, 4096)
  }

  // ==========================================================================
  // The standard dialect
  // ==========================================================================

  it should "send plain string content and omit empty assistant content" in {
    val c = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(config("http://localhost:1/v1")),
      OpenAICompatibleDialect.Standard
    )
    val body = c.createRequestBody(
      Conversation(
        Seq(
          SystemMessage("sys"),
          UserMessage("hi"),
          AssistantMessage(None, List(ToolCall("c1", "f", ujson.Obj("a" -> 1)))),
          ToolMessage("out", "c1")
        )
      ),
      CompletionOptions(maxTokens = Some(10)).withReasoning(ReasoningEffort.High)
    )
    body("messages")(0)("content").str shouldBe "sys"
    body("messages")(1)("content").str shouldBe "hi"
    body("messages")(2).obj.contains("content") shouldBe false
    body("messages")(2)("tool_calls")(0)("function")("arguments").str shouldBe """{"a":1}"""
    body("messages")(3)("content").str shouldBe "out"
    body("max_tokens").num shouldBe 10
    body.obj.keySet should contain noneOf ("thinking", "reasoning_effort", "stream")
  }

  it should "ignore reasoning fields in a reply" in {
    val c = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(config("http://localhost:1/v1")),
      OpenAICompatibleDialect.Standard
    )
    val completion = c.parseCompletion(
      ujson.read(
        """{"id":"x","created":1,"model":"m","choices":[{"message":{"content":"a","reasoning_content":"r","thinking":"t"}}]}"""
      )
    )
    completion.thinking shouldBe None
    c.parseStreamingChunks(ujson.read("""{"choices":[{"delta":{"reasoning_content":"r"}}]}"""))
      .head
      .thinkingDelta shouldBe None
  }

  it should "read a reply that omits id, created and model, and usage given as an array" in {
    val c = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(config("http://localhost:1/v1")),
      OpenAICompatibleDialect.Standard
    )
    val completion = c.parseCompletion(
      ujson.read(
        """{"choices":[{"message":{"content":null}}],"usage":[{"prompt_tokens":3,"completion_tokens":4}]}"""
      )
    )
    completion.id shouldBe ""
    completion.created shouldBe 0L
    completion.model shouldBe "local-model"
    completion.content shouldBe ""
    completion.message.contentOpt shouldBe None
    completion.usage shouldBe Some(TokenUsage(3, 4, 7))
  }

  it should "parse tool calls leniently" in {
    OpenAICompatibleDialect.lenientToolCalls(
      ujson.read(
        """[{"id":"a","function":{"name":"f","arguments":"{\"x\":1}"}},{"function":{"arguments":"{bad"}},{}]"""
      )
    ) shouldBe Seq(
      ToolCall("a", "f", ujson.Obj("x" -> 1)),
      ToolCall("", "", ujson.Obj()),
      ToolCall("", "", ujson.Obj())
    )
    OpenAICompatibleDialect.lenientToolCalls(ujson.Null) shouldBe empty
  }

  it should "skip a streamed event with no choices" in {
    val c = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(config("http://localhost:1/v1")),
      OpenAICompatibleDialect.Standard
    )
    c.parseStreamingChunks(ujson.read("""{"id":"u","usage":{"prompt_tokens":1}}""")) shouldBe empty
    c.parseStreamingChunks(ujson.read("""{"id":"u","choices":[]}""")) shouldBe empty
  }

  // ==========================================================================
  // Errors, and one exchange per call
  // ==========================================================================

  it should "map an error status and record the exchange once" in {
    val (recorded, logging) = recordingSink()
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 401, """{"error":"nope"}""")) { baseUrl =>
      client(config(baseUrl), logging).complete(conversation, CompletionOptions()).left.value shouldBe an[
        AuthenticationError
      ]
    }
    recorded should have size 1
    recorded.head.provider shouldBe "openai-compatible"
    recorded.head.responseBody shouldBe Some("""{"error":"nope"}""")
  }

  it should "return a Left for a malformed 200 reply, and record it once" in {
    val (recorded, logging) = recordingSink()
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, "not json")) { baseUrl =>
      client(config(baseUrl), logging).complete(conversation, CompletionOptions()).isLeft shouldBe true
    }
    recorded should have size 1
    recorded.head.errorMessage shouldBe defined
  }

  it should "record exactly one exchange when the request cannot be sent" in {
    val (recorded, logging) = recordingSink()
    val c                   = client(config(deadBaseUrl), logging)

    c.complete(conversation, CompletionOptions()).isLeft shouldBe true
    c.streamComplete(conversation, CompletionOptions(), _ => ()).isLeft shouldBe true

    recorded should have size 2
    recorded.foreach(_.requestBody should include("hello"))
    recorded.map(_.responseBody) shouldBe Seq(None, None)
  }

  it should "record a streaming error status with its body" in {
    val (recorded, logging) = recordingSink()
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 503, """{"error":"busy"}""")) { baseUrl =>
      val result = client(config(baseUrl), logging).streamComplete(conversation, CompletionOptions(), _ => ())
      result.left.value shouldBe a[ServiceError]
    }
    recorded should have size 1
    recorded.head.responseBody shouldBe Some("""{"error":"busy"}""")
  }

  // ==========================================================================
  // The stream body is closed on every path (drift fix, #1132)
  // ==========================================================================

  "consumeStream" should "close the body on an error status" in {
    val body = new TrackingBody("""{"error":"rate limited"}""")
    streamingClient.consumeStream(429, body, new StringBuilder, _ => ()).isLeft shouldBe true
    body.closed shouldBe true
  }

  it should "close the body when an event is malformed" in {
    val body = new TrackingBody("data: {not json\n\n")
    streamingClient.consumeStream(200, body, new StringBuilder, _ => ()).isLeft shouldBe true
    body.closed shouldBe true
  }

  it should "close the body when the chunk callback throws" in {
    val body = new TrackingBody(openAISseBody(Seq("a", "b")))
    val result =
      streamingClient.consumeStream(200, body, new StringBuilder, _ => throw new IllegalStateException("boom"))
    result.left.value.message should include("boom")
    body.closed shouldBe true
  }

  it should "close the body on success, keeping the raw stream" in {
    val body = new TrackingBody(openAISseBody(Seq("a", "b")))
    val raw  = new StringBuilder
    streamingClient.consumeStream(200, body, raw, _ => ()).value.content shouldBe "ab"
    body.closed shouldBe true
    raw.result() should include("[DONE]")
  }
}
