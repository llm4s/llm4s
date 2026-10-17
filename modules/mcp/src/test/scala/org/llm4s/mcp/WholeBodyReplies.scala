package org.llm4s.mcp

import org.llm4s.http._
import org.llm4s.types.Result

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

/**
 * Lets a spec that stubs `Llm4sHttpClient.post` with a whole reply drive [[StreamableHTTPTransportImpl]],
 * which reads its reply through `postStream` so that the reply's body is bounded by the timeout too (#1785).
 *
 * `postStream` is answered by calling the stub's `post` with the same arguments and handing back its body as
 * a stream, so the stubs, their argument matchers and any verification of `post` keep their meaning. Every
 * other method goes to the stub unchanged.
 */
private[mcp] object WholeBodyReplies {

  def apply(stub: Llm4sHttpClient): Llm4sHttpClient = new Llm4sHttpClient {
    override def get(
      url: String,
      headers: Map[String, String],
      params: Map[String, String],
      timeout: FiniteDuration
    ): Result[HttpResponse] = stub.get(url, headers, params, timeout)

    override def post(
      url: String,
      headers: Map[String, String],
      body: String,
      timeout: FiniteDuration
    ): Result[HttpResponse] = stub.post(url, headers, body, timeout)

    override def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Result[HttpResponse] = stub.postBytes(url, headers, data, timeout)

    override def postMultipart(
      url: String,
      headers: Map[String, String],
      parts: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Result[HttpResponse] = stub.postMultipart(url, headers, parts, timeout)

    override def put(
      url: String,
      headers: Map[String, String],
      body: String,
      timeout: FiniteDuration
    ): Result[HttpResponse] = stub.put(url, headers, body, timeout)

    override def delete(url: String, headers: Map[String, String], timeout: FiniteDuration): Result[HttpResponse] =
      stub.delete(url, headers, timeout)

    override def postRaw(
      url: String,
      headers: Map[String, String],
      body: String,
      timeout: FiniteDuration
    ): Result[HttpRawResponse] = stub.postRaw(url, headers, body, timeout)

    override def postStream(
      url: String,
      headers: Map[String, String],
      body: String,
      timeout: FiniteDuration
    ): Result[StreamingHttpResponse] =
      stub
        .post(url, headers, body, timeout)
        .map { response =>
          val bytes = Option(response.body).getOrElse("").getBytes(StandardCharsets.UTF_8)
          StreamingHttpResponse(response.statusCode, new ByteArrayInputStream(bytes), response.headers)
        }
  }
}
