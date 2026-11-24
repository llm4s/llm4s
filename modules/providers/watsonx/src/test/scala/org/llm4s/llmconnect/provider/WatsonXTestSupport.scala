package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.http.{ HttpBody, HttpRequest, HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.types.Result

import java.io.{ ByteArrayInputStream, InputStream }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/** One request a [[StubHttp]] saw. */
final case class Seen(
  url: String,
  headers: Map[String, String],
  body: String,
  streaming: Boolean,
  timeout: FiniteDuration
)

/**
 * A thread-safe scripted [[Llm4sHttpClient]]: `core`'s `MockHttpClient` mutates plain vars and a
 * queue, so it cannot back a concurrency test. Every POST is answered by `respond` (which decides
 * by URL), and recorded.
 */
final class StubHttp(
  respond: Seen => Result[HttpResponse],
  streamBody: Seen => Result[StreamingHttpResponse] = _ =>
    Left(org.llm4s.error.ConfigurationError("no stream scripted"))
) extends Llm4sHttpClient {
  private val log = new ConcurrentLinkedQueue[Seen]()

  def requests: Seq[Seen]      = log.asScala.toSeq
  def iamRequests: Seq[Seen]   = requests.filter(_.url.contains("/identity/token"))
  def modelRequests: Seq[Seen] = requests.filterNot(_.url.contains("/identity/token"))
  private val closedFlag       = new AtomicBoolean(false)
  def closed: Boolean          = closedFlag.get()
  override def close(): Unit   = closedFlag.set(true)

  /** A request that asks for `text/event-stream` is answered from the stream script, any other from the reply script. */
  override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    val streaming = request.headers.get("Accept").contains("text/event-stream")
    val body = request.body match {
      case HttpBody.Text(text) => text
      case _                   => ""
    }
    val seen = Seen(request.url, request.headers, body, streaming, request.responseTimeout)
    log.add(seen)
    if streaming then streamBody(seen) else respond(seen).map(_.toStreaming)
  }
}

object StubHttp {
  val IamUrl = "https://iam.example.com/identity/token"

  def iamToken(token: String = "tok-1", expiresIn: Int = 3600): HttpResponse =
    HttpResponse(200, s"""{"access_token":"$token","expires_in":$expiresIn}""")

  /** A chat reply: the text `Hello`, a normal stop and seven prompt and two completion tokens. */
  val chatReply: HttpResponse = HttpResponse(
    200,
    """{"id":"c-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""
  )

  /** IAM answers `iam`; model calls answer `model`. */
  def routed(iam: Seen => Result[HttpResponse], model: Seen => Result[HttpResponse]): StubHttp =
    new StubHttp(seen => if seen.url.contains("/identity/token") then iam(seen) else model(seen))

  /** IAM always succeeds with `tok-1`; the streaming call is answered by `stream`. */
  def streaming(stream: Seen => Result[StreamingHttpResponse]): StubHttp =
    new StubHttp(_ => Right(iamToken()), stream)

  def streamOf(in: InputStream, status: Int = 200): Seen => Result[StreamingHttpResponse] =
    _ => Right(StreamingHttpResponse(status, in))

  def bytes(text: String): InputStream = new ByteArrayInputStream(text.getBytes("UTF-8"))
}

object WatsonXTestConfig {
  val ApiKey = "SUPERSECRETAPIKEY-0123456789"

  val config: WatsonXConfig = WatsonXConfig(
    apiKey = Secret(ApiKey),
    projectId = "project-1",
    spaceId = None,
    model = ModelName("ibm/granite-13b-instruct-v2"),
    baseUrl = BaseUrl("https://wx.example.com"),
    apiVersion = "2026-09-25",
    iamUrl = StubHttp.IamUrl,
    contextWindow = 8192,
    reserveCompletion = 1024
  )
}
