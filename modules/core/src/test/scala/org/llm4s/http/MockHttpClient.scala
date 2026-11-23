package org.llm4s.http

import org.llm4s.error.LLMError
import org.llm4s.types.Result

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

final class MockHttpClient(response: HttpResponse) extends Llm4sHttpClient {
  def this(responses: Seq[HttpResponse]) =
    this(responses.headOption.getOrElse(HttpResponse(200, "", Map.empty)))
    enqueueResponses(responses.drop(1))

  var lastUrl: Option[String]                                      = None
  var lastHeaders: Option[Map[String, String]]                     = None
  var lastParams: Option[Map[String, String]]                      = None
  var lastBody: Option[String]                                     = None
  var lastTimeout: Option[FiniteDuration]                          = None
  var postCallCount: Int                                           = 0
  @volatile var closed: Boolean                                    = false
  val posts: mutable.Buffer[(String, Map[String, String], String)] = mutable.Buffer.empty
  val getRequests: mutable.Buffer[(String, Map[String, String], Map[String, String], FiniteDuration)] =
    mutable.Buffer.empty

  /** Every request, in the order sent. */
  val requests: mutable.Buffer[HttpRequest] = mutable.Buffer.empty

  private val queuedResponses: mutable.Queue[HttpResponse] = mutable.Queue(response)

  def enqueueResponses(responses: Seq[HttpResponse]): Unit =
    queuedResponses.enqueueAll(responses)

  private def nextResponse: HttpResponse =
    if queuedResponses.sizeCompare(1) > 0 then queuedResponses.dequeue()
    else queuedResponses.head

  override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    val text = request.body match {
      case HttpBody.Text(value) => Some(value)
      case _                    => None
    }
    requests.append(request)
    lastUrl = Some(request.url)
    lastHeaders = Some(request.headers)
    lastParams = Option.when(request.method == HttpMethod.Get)(request.queryParams)
    lastBody = text
    lastTimeout = Some(request.responseTimeout)
    if (request.method == HttpMethod.Get)
      getRequests.append((request.url, request.headers, request.queryParams, request.responseTimeout))
    if (request.method == HttpMethod.Post) {
      postCallCount += 1
      posts.append((request.url, request.headers, text.getOrElse("")))
    }
    val r = nextResponse
    Right(
      StreamingHttpResponse(r.statusCode, new ByteArrayInputStream(r.body.getBytes(StandardCharsets.UTF_8)), r.headers)
    )
  }

  override def close(): Unit = closed = true
}

/**
 * Test double whose every request fails as [[JdkHttpClient]] reports a transport failure.
 *
 * Built from an [[LLMError]], every call returns it. Built from a `Throwable`, every call
 * runs it through the JDK client's own guard (`HttpFailures.attempt`), so a spec can say
 * "the connection was refused" and get the `NetworkError` a real refusal produces - and an
 * `InterruptedException` restores the caller's interrupt flag, as the real client does.
 */
final class FailingHttpClient private (failure: () => Result[Nothing]) extends Llm4sHttpClient {
  def this(error: LLMError) = this(() => Left(error))

  def this(exception: Throwable) =
    this(() => HttpFailures.attempt("HTTP", "http://failing.invalid/", Duration.Zero)(throw exception))

  override def send(request: HttpRequest): Result[StreamingHttpResponse] = failure()
}

/**
 * An [[Llm4sHttpClient]] a ScalaMock `stub` can script call by call.
 *
 * The client's own `get`, `post` and the rest are final conveniences over `send`, so a stub cannot override
 * them. It overrides these `on*` methods instead - one per convenience, with the same parameters - and `send`
 * routes each request to the one that matches its method and body:
 *
 * {{{
 * val http = stub[StubbedHttpCalls]
 * (http.onPost _).when(*, *, *, *).returns(Right(HttpResponse(200, "{}")))
 * }}}
 *
 * A POST of text may be a `post`, a `postRaw` or a `postStream`: one request to `send`, three different stubs
 * here. `send` therefore looks up its own call stack for the convenience it was called through and routes to
 * that one's stub; a text POST sent with `send`, `sendText` or `sendRaw` directly goes to `onPost`. `send` is
 * final so that a stub, which overrides every method it can, leaves it alone.
 */
trait StubbedHttpCalls extends Llm4sHttpClient {

  def onGet(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse]

  def onPost(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration): Result[HttpResponse]

  def onPostBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse]

  def onPostMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse]

  def onPut(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration): Result[HttpResponse]

  def onDelete(url: String, headers: Map[String, String], timeout: FiniteDuration): Result[HttpResponse]

  def onPostRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse]

  def onPostStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse]

  final override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    import request.{ headers, url }
    val timeout = request.responseTimeout
    def text(result: Result[HttpResponse]): Result[StreamingHttpResponse] =
      result.map(r => StubbedHttpCalls.streaming(r.statusCode, r.body.getBytes(StandardCharsets.UTF_8), r.headers))

    (request.method, request.body) match {
      case (HttpMethod.Get, _)                          => text(onGet(url, headers, request.queryParams, timeout))
      case (HttpMethod.Delete, _)                       => text(onDelete(url, headers, timeout))
      case (HttpMethod.Put, body)                       => text(onPut(url, headers, StubbedHttpCalls.textOf(body), timeout))
      case (HttpMethod.Post, HttpBody.Bytes(data))      => text(onPostBytes(url, headers, data, timeout))
      case (HttpMethod.Post, HttpBody.Multipart(parts)) => text(onPostMultipart(url, headers, parts, timeout))
      case (HttpMethod.Post, body) =>
        val sent = StubbedHttpCalls.textOf(body)
        StubbedHttpCalls.postConvenience() match {
          case "postStream" => onPostStream(url, headers, sent, timeout)
          case "postRaw" =>
            onPostRaw(url, headers, sent, timeout).map(r => StubbedHttpCalls.streaming(r.statusCode, r.body, r.headers))
          case _ => text(onPost(url, headers, sent, timeout))
        }
      case (other, _) =>
        Left(org.llm4s.error.ValidationError("request", s"StubbedHttpCalls scripts no ${other.name} request"))
    }
  }
}

object StubbedHttpCalls {
  private val PostConveniences = Set("postStream", "postRaw", "post")

  /** Which of the client's text-POST conveniences is calling `send`: the nearest one on the stack, else `post`. */
  private def postConvenience(): String =
    StackWalker
      .getInstance()
      .walk(_.map(_.getMethodName.stripSuffix("$")).filter(PostConveniences.contains(_)).findFirst())
      .orElse("post")

  private def streaming(status: Int, body: Array[Byte], headers: Map[String, Seq[String]]): StreamingHttpResponse =
    StreamingHttpResponse(status, new ByteArrayInputStream(body), headers)

  private def textOf(body: HttpBody): String = body match {
    case HttpBody.Text(value) => value
    case _                    => ""
  }
}
