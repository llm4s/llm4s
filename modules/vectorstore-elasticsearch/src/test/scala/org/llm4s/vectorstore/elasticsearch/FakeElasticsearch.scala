package org.llm4s.vectorstore.elasticsearch

import org.llm4s.http.{ HttpBody, HttpRequest, HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.types.Result

import scala.collection.mutable

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

  override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    val body = request.body match {
      case HttpBody.Text(text) => text
      case _                   => ""
    }
    handle(request.method.name, request.url, request.headers, body).map(_.toStreaming)
  }

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
