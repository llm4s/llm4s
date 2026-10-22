package org.llm4s.vectorstore.elasticsearch

import org.llm4s.error.ValidationError
import org.llm4s.http.{ HttpRawResponse, HttpResponse, Llm4sHttpClient, MultipartPart, StreamingHttpResponse }
import org.llm4s.types.Result

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** One request the store sent. */
final case class SentRequest(method: String, url: String, headers: Map[String, String], body: String) {
  def json: ujson.Value = ujson.read(body)

  /** The NDJSON lines of a `_bulk` body, each parsed. */
  def ndjson: Seq[ujson.Value] = body.split('\n').toSeq.filter(_.nonEmpty).map(ujson.read(_))
}

/**
 * A fake Elasticsearch: answers each request from `respond`, by method and URL, and records it. A request `respond`
 * does not cover is answered 599, which no test expects, so it shows up as a failure.
 */
final class FakeElasticsearch(respond: PartialFunction[(String, String), Result[HttpResponse]])
    extends Llm4sHttpClient {

  val requests: mutable.Buffer[SentRequest] = mutable.ArrayBuffer.empty
  @volatile var closed: Boolean             = false

  private def handle(method: String, url: String, headers: Map[String, String], body: String): Result[HttpResponse] = {
    requests += SentRequest(method, url, headers, body)
    respond.applyOrElse((method, url), (_: (String, String)) => Right(HttpResponse(599, s"unexpected $method $url")))
  }

  def sent(method: String, url: String): Seq[SentRequest] =
    requests.filter(r => r.method == method && r.url == url).toSeq

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] = handle("GET", url, headers, "")

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = handle("POST", url, headers, body)

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = handle("PUT", url, headers, body)

  override def delete(url: String, headers: Map[String, String], timeout: FiniteDuration): Result[HttpResponse] =
    handle("DELETE", url, headers, "")

  private def unused: Left[ValidationError, Nothing] = Left(ValidationError("request", "not used by the store"))

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] = unused

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] = unused

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] = unused

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] = unused

  override def close(): Unit = closed = true
}

object FakeElasticsearch {

  def ok(body: String): Result[HttpResponse] = Right(HttpResponse(200, body))

  def ok(body: ujson.Value): Result[HttpResponse] = ok(ujson.write(body))

  def status(code: Int, body: String = "{}"): Result[HttpResponse] = Right(HttpResponse(code, body))

  val IndexNotFound: Result[HttpResponse] =
    status(
      404,
      """{"error":{"root_cause":[{"type":"index_not_found_exception","reason":"no such index [vectors]"}],""" +
        """"type":"index_not_found_exception","reason":"no such index [vectors]"},"status":404}"""
    )

  val BulkOk: Result[HttpResponse] = ok("""{"took":3,"errors":false,"items":[]}""")
}
