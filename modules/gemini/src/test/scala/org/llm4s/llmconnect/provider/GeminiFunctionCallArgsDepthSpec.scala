package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.{ LLMClient, ProviderExchangeLogging }
import org.llm4s.llmconnect.config.{ GeminiConfig, VertexAIConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.SmallStack
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * A `functionCall.args` is the model's own JSON, and unlike a chat-completions `arguments` string it sits in
 * the response envelope as a native object, so the envelope parse is the only boundary it crosses. That
 * parse is iterative and admits any depth; what overflows is rendering the call back on the next turn (and,
 * for a signed call, storing the part for replay), once per level. The envelope is therefore read through
 * the 512-level bound every other model-written JSON has (#1562), for Gemini and Vertex AI alike - and the
 * streamed reply fails the same way the non-streamed one does: a chunk over the limit fails the stream,
 * rather than vanishing from a completion that then reports success without its call.
 */
class GeminiFunctionCallArgsDepthSpec extends AnyFlatSpec with Matchers with MockFactory {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val geminiConfig = GeminiConfig(
    apiKey = "test-key",
    model = "gemini-2.0-flash",
    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private val vertexConfig = VertexAIConfig(
    projectId = "my-gcp-project",
    location = "us-central1",
    model = "gemini-2.0-flash",
    credentialFilePath = None,
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  private val tokenBody = """{"access_token":"ya29.test-token","expires_in":3600}"""

  /** `{"a":{"a":...1...}}`, `n` objects deep: well past the limit, and certain to overflow a 1 MB stack. */
  private val deepArgs: String = "{\"a\":" * 100000 + "1" + "}" * 100000

  private def callReply(args: String): String =
    s"""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":$args}}],"role":"model"},
       |"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}""".stripMargin
  private val textReply =
    """{"candidates":[{"content":{"parts":[{"text":"Sunny"}],"role":"model"},"finishReason":"STOP"}]}"""

  private def sse(json: String*): ByteArrayInputStream =
    new ByteArrayInputStream(
      json.map(j => s"data: ${j.replace("\n", "")}\n").mkString.getBytes(StandardCharsets.UTF_8)
    )

  /** Gemini: every POST returns `reply`. */
  private def gemini(mockHttp: Llm4sHttpClient): LLMClient =
    new GeminiClient(geminiConfig, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)

  /** Vertex AI: the OAuth token endpoint answers with a token, every other POST with `reply`. */
  private def vertex(mockHttp: Llm4sHttpClient, reply: String): LLMClient = {
    (mockHttp.post _).when(*, *, *, *).onCall {
      (url: String, _: Map[String, String], _: String, _: scala.concurrent.duration.FiniteDuration) =>
        Right(
          if (url.contains("oauth2.googleapis.com")) HttpResponse(200, tokenBody, Map.empty)
          else HttpResponse(200, reply, Map.empty)
        )
    }
    (mockHttp.get _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    new VertexAIClient(
      vertexConfig,
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Disabled,
      mockHttp
    )
  }

  private val question = Conversation(Seq(UserMessage("weather?")))

  /**
   * The reply, then - if it was accepted - the turn that answers its call, which renders the call back.
   * On a 1 MB stack, so an overflow in either is a Left here, whatever the JVM's default stack size.
   */
  private def roundTrip(client: LLMClient)(first: => org.llm4s.types.Result[Completion]) =
    SmallStack.run {
      first match {
        case Right(completion) =>
          val answered = completion.toolCalls.map(c => ToolMessage("Sunny", c.id))
          client
            .complete(Conversation(question.messages ++ Seq(completion.message) ++ answered), CompletionOptions())
            .map(_ => s"a completion with ${completion.toolCalls.size} call(s), sent back")
            .left
            .map(e => (e.getClass.getSimpleName, e.message))
        case Left(e) => Left((e.getClass.getSimpleName, e.message))
      }
    }

  private def shouldRefuse(outcome: Either[Throwable, Either[(String, String), String]]): Unit =
    outcome match {
      case Right(Left((kind, message))) =>
        kind shouldBe "ValidationError"
        message should include("512")
      case Right(Right(other)) => fail(s"expected a Left, but the reply was accepted: $other")
      case Left(thrown)        => fail(s"expected a Left, but the round trip threw $thrown")
    }

  "GeminiClient.complete" should "refuse a functionCall whose args are nested too deeply, never overflow" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(200, callReply(deepArgs), Map.empty)))
    val client = gemini(mockHttp)
    shouldRefuse(roundTrip(client)(client.complete(question, CompletionOptions())))
  }

  "GeminiClient.streamComplete" should "fail the stream on a chunk whose functionCall args are nested too deeply, never overflow" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, sse(callReply(deepArgs)))))
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(200, textReply, Map.empty)))
    val client = gemini(mockHttp)
    shouldRefuse(roundTrip(client)(client.streamComplete(question, CompletionOptions(), _ => ())))
  }

  it should "still skip a chunk that is not JSON at all, as before" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _)
      .when(*, *, *, *)
      .returns(Right(StreamingHttpResponse(200, sse("{not json", textReply))))
    val completion =
      gemini(mockHttp).streamComplete(question, CompletionOptions(), _ => ()).fold(e => fail(e.message), identity)
    completion.content shouldBe "Sunny"
  }

  "VertexAIClient.complete" should "refuse a functionCall whose args are nested too deeply, never overflow" in {
    val mockHttp = stub[Llm4sHttpClient]
    val client   = vertex(mockHttp, callReply(deepArgs))
    shouldRefuse(roundTrip(client)(client.complete(question, CompletionOptions())))
  }

  "VertexAIClient.streamComplete" should "fail the stream on a chunk whose functionCall args are nested too deeply, never overflow" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).returns(Right(StreamingHttpResponse(200, sse(callReply(deepArgs)))))
    val client = vertex(mockHttp, textReply)
    shouldRefuse(roundTrip(client)(client.streamComplete(question, CompletionOptions(), _ => ())))
  }

  it should "still skip a chunk that is not JSON at all, as before" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.postStream _)
      .when(*, *, *, *)
      .returns(Right(StreamingHttpResponse(200, sse("{not json", textReply))))
    val completion =
      vertex(mockHttp, textReply)
        .streamComplete(question, CompletionOptions(), _ => ())
        .fold(e => fail(e.message), identity)
    completion.content shouldBe "Sunny"
  }

  "GeminiClient" should "still accept a functionCall whose args are nested to the limit" in {
    // the limit is the reply's: the envelope around `args` is itself seven levels deep
    val atLimit  = "{\"a\":" * 505 + "1" + "}" * 505
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(200, callReply(atLimit), Map.empty)))
    val completion = gemini(mockHttp).complete(question, CompletionOptions()).fold(e => fail(e.message), identity)
    completion.toolCalls.map(_.name) shouldBe List("get_weather")
    completion.toolCalls.head.arguments.obj.keySet shouldBe Set("a")
  }
}
