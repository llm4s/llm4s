package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.ConfigurationError
import org.llm4s.http.{ HttpBody, HttpRequest, HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.config.PerplexityConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** A request the stub saw. */
final case class Seen(url: String, headers: Map[String, String], body: String, streaming: Boolean)

/** An [[Llm4sHttpClient]] that records requests and answers from a script, for the client's unit specs. */
final class StubHttp(
  respond: Seen => Result[HttpResponse] = _ => Left(ConfigurationError("no reply scripted")),
  stream: Seen => Result[StreamingHttpResponse] = _ => Left(ConfigurationError("no stream scripted"))
) extends Llm4sHttpClient {
  private val log = new ConcurrentLinkedQueue[Seen]()

  def requests: Seq[Seen]       = log.asScala.toSeq
  def lastBody: ujson.Value     = ujson.read(requests.last.body)
  @volatile var closed: Boolean = false
  override def close(): Unit    = closed = true

  /** A request that asks for `text/event-stream` is answered from the stream script, any other from the reply script. */
  override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    val streaming = request.headers.get("Accept").contains("text/event-stream")
    val body = request.body match {
      case HttpBody.Text(text) => text
      case _                   => ""
    }
    val seen = Seen(request.url, request.headers, body, streaming)
    log.add(seen)
    if streaming then stream(seen) else respond(seen).map(_.toStreaming)
  }
}

object PerplexityFixtures {

  given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  def config(
    model: String = "perplexity/sonar",
    webSearch: Boolean = true,
    headers: Map[String, String] = Map.empty,
    baseUrl: String = PerplexityConfig.DEFAULT_BASE_URL,
    reserveCompletion: Int = PerplexityConfig.DEFAULT_RESERVE_COMPLETION
  ): PerplexityConfig =
    PerplexityConfig
      .fromValues(
        model = ModelName(model),
        apiKey = Secret("pplx-test-key"),
        baseUrl = baseUrl,
        webSearch = webSearch,
        reserveCompletion = reserveCompletion,
        headers = headers
      )
      .fold(e => throw new IllegalStateException(e.message), identity)

  def client(http: StubHttp, cfg: PerplexityConfig = config()): PerplexityClient =
    new PerplexityClient(cfg, httpClient = http)

  def ok(body: String): Result[HttpResponse] = Right(HttpResponse(200, body))

  def sse(events: String*): Result[StreamingHttpResponse] =
    Right(StreamingHttpResponse(200, new ByteArrayInputStream(sseBody(events*).getBytes(StandardCharsets.UTF_8))))

  /** Each event as one SSE frame, `event:` and `data:` lines. */
  def sseBody(events: String*): String =
    events.map { e =>
      val kind = ujson.read(e).obj.get("type").flatMap(_.strOpt).getOrElse("message")
      s"event: $kind\ndata: $e\n\n"
    }.mkString

  /** A completed Agent API response with `output` items and usage. */
  def response(
    output: String,
    status: String = "completed",
    usage: String =
      """{"input_tokens":12,"output_tokens":5,"total_tokens":17,"cost":{"currency":"USD","input_cost":0.001,"output_cost":0.002,"total_cost":0.0035}}"""
  ): String =
    s"""{"id":"resp_1","created_at":1779391454,"model":"perplexity/sonar","object":"response","status":"$status","output":$output,"usage":$usage}"""

  def textMessage(text: String, annotations: String = "[]"): String =
    s"""{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":${ujson
        .Str(text)
        .render()},"annotations":$annotations}]}"""

  val searchResults: String =
    """{"type":"search_results","queries":["q"],"results":[{"id":1,"url":"https://a.example/1","title":"A","snippet":"alpha","date":"2026-01-01","source":"web"},{"id":2,"url":"https://b.example/2","title":"B","snippet":"beta","source":"web"}]}"""

  def functionCall(callId: String, name: String, arguments: String, signature: Option[String] = None): String =
    val sig = signature.fold("")(s => s""","thought_signature":"$s"""")
    s"""{"id":"fc_1","type":"function_call","status":"completed","call_id":"$callId","name":"$name","arguments":${ujson
        .Str(arguments)
        .render()}$sig}"""

  def completedEvent(responseJson: String): String =
    s"""{"type":"response.completed","sequence_number":9,"response":$responseJson}"""

  def delta(text: String): String =
    s"""{"type":"response.output_text.delta","sequence_number":3,"item_id":"msg_1","output_index":0,"content_index":0,"delta":${ujson
        .Str(text)
        .render()}}"""

  val created: String =
    """{"type":"response.created","sequence_number":0,"response":{"id":"resp_s","object":"response","created_at":1,"status":"in_progress","model":"perplexity/sonar","output":[]}}"""
}
