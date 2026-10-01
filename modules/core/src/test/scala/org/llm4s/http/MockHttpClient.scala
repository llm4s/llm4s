package org.llm4s.http

import org.llm4s.error.LLMError
import org.llm4s.types.Result

import scala.collection.mutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

final class MockHttpClient(response: HttpResponse) extends Llm4sHttpClient {
  def this(responses: Seq[HttpResponse]) =
    this(responses.headOption.getOrElse(HttpResponse(200, "", Map.empty)))
    enqueueResponses(responses.drop(1))

  var lastUrl: Option[String]                  = None
  var lastHeaders: Option[Map[String, String]] = None
  var lastParams: Option[Map[String, String]]  = None
  var lastBody: Option[String]                 = None
  var lastTimeout: Option[FiniteDuration]      = None
  var postCallCount: Int                       = 0
  val getRequests: mutable.Buffer[(String, Map[String, String], Map[String, String], FiniteDuration)] =
    mutable.Buffer.empty

  private val queuedResponses: mutable.Queue[HttpResponse] = mutable.Queue(response)

  def enqueueResponses(responses: Seq[HttpResponse]): Unit =
    queuedResponses.enqueueAll(responses)

  private def nextResponse: HttpResponse =
    if queuedResponses.sizeCompare(1) > 0 then queuedResponses.dequeue()
    else queuedResponses.head

  private def record(
    url: String,
    headers: Map[String, String],
    params: Option[Map[String, String]],
    body: Option[String],
    timeout: FiniteDuration,
    countPost: Boolean
  ): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastParams = params
    lastBody = body
    lastTimeout = Some(timeout)
    params.foreach(ps => getRequests.append((url, headers, ps, timeout)))
    if (countPost) {
      postCallCount += 1
    }
    nextResponse
  }

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    Right(record(url, headers, Some(params), None, timeout, countPost = false))

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    Right(record(url, headers, None, Some(body), timeout, countPost = true))

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    Right(record(url, headers, None, None, timeout, countPost = true))

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    Right(record(url, headers, None, None, timeout, countPost = true))

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    Right(record(url, headers, None, Some(body), timeout, countPost = false))

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] =
    Right(record(url, headers, None, None, timeout, countPost = false))

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] = {
    val r = record(url, headers, None, Some(body), timeout, countPost = true)
    Right(HttpRawResponse(r.statusCode, r.body.getBytes(), r.headers))
  }

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] = {
    val r = record(url, headers, None, Some(body), timeout, countPost = true)
    Right(StreamingHttpResponse(r.statusCode, new java.io.ByteArrayInputStream(r.body.getBytes()), r.headers))
  }
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

  private def fail: Result[Nothing] = failure()

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] = fail

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = fail

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] = fail

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] = fail

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = fail

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] = fail

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] = fail

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] = fail
}
