package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpRawResponse, HttpResponse, Llm4sHttpClient, MultipartPart, StreamingHttpResponse }
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

  def requests: Seq[Seen]          = log.asScala.toSeq
  def iamRequests: Seq[Seen]       = requests.filter(_.url.contains("/identity/token"))
  def modelRequests: Seq[Seen]     = requests.filterNot(_.url.contains("/identity/token"))
  private val closedFlag           = new AtomicBoolean(false)
  def closed: Boolean              = closedFlag.get()
  override def close(): Unit       = closedFlag.set(true)
  private def unsupported: Nothing = throw new UnsupportedOperationException("not used by watsonx")

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = {
    val seen = Seen(url, headers, body, streaming = false, timeout)
    log.add(seen)
    respond(seen)
  }

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] = {
    val seen = Seen(url, headers, body, streaming = true, timeout)
    log.add(seen)
    streamBody(seen)
  }

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] = unsupported
  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] = unsupported
  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] = unsupported
  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = unsupported
  override def delete(url: String, headers: Map[String, String], timeout: FiniteDuration): Result[HttpResponse] =
    unsupported
  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] = unsupported
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
    apiKey = ApiKey,
    projectId = "project-1",
    spaceId = None,
    model = "ibm/granite-13b-instruct-v2",
    baseUrl = "https://wx.example.com",
    apiVersion = "2024-05-31",
    iamUrl = StubHttp.IamUrl,
    contextWindow = 8192,
    reserveCompletion = 1024
  )
}
