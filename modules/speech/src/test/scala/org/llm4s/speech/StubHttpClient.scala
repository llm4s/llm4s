package org.llm4s.speech

import org.llm4s.error.LLMError
import org.llm4s.http._
import org.llm4s.types.Result

import java.nio.file.{ Files, Path }
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * Test double for the HTTP layer of the cloud speech clients: no network.
 *
 * Answers every request with `status` / `body` / `responseHeaders`, or fails it with `failure`
 * (the `Left` a real client returns for a timeout or a refused connection). Binary bodies are
 * served byte for byte, so a test can tell a client that decodes audio as text from one that does
 * not. Every request is recorded, including the content of multipart file parts at the time of
 * the call (the client may delete its temporary file straight after).
 */
final class StubHttpClient(
  status: Int = 200,
  body: Array[Byte] = Array.emptyByteArray,
  responseHeaders: Map[String, Seq[String]] = Map.empty,
  failure: Option[LLMError] = None
) extends Llm4sHttpClient {

  final case class Recorded(
    method: String,
    url: String,
    headers: Map[String, String],
    body: Array[Byte],
    parts: Seq[MultipartPart],
    fileContents: Map[String, Array[Byte]],
    filePaths: Map[String, Path]
  ) {
    def text: String = new String(body, "UTF-8")
    def field(name: String): Option[String] =
      parts.collectFirst { case MultipartPart.TextField(`name`, v) => v }
  }

  val requests: mutable.Buffer[Recorded] = mutable.Buffer.empty

  def only: Recorded = {
    require(requests.size == 1, s"expected exactly one request, saw ${requests.size}")
    requests.head
  }

  private def record(
    method: String,
    url: String,
    headers: Map[String, String],
    requestBody: Array[Byte] = Array.emptyByteArray,
    parts: Seq[MultipartPart] = Seq.empty
  ): Unit = {
    val files = parts.collect { case MultipartPart.FilePart(n, path, _) => n -> path }
    requests += Recorded(
      method,
      url,
      headers,
      requestBody,
      parts,
      files.map { case (n, path) => n -> Files.readAllBytes(path) }.toMap,
      files.toMap
    )
  }

  private def text: Result[HttpResponse] =
    failure.toLeft(HttpResponse(status, new String(body, "UTF-8"), responseHeaders))

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: FiniteDuration
  ): Result[HttpResponse] = { record("GET", url, headers); text }

  override def post(
    url: String,
    headers: Map[String, String],
    b: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = {
    record("POST", url, headers, b.getBytes("UTF-8")); text
  }

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: FiniteDuration
  ): Result[HttpResponse] = { record("POST", url, headers, data); text }

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: FiniteDuration
  ): Result[HttpResponse] = {
    // As the JDK client does, an unreadable file part is a ValidationError, not an exception.
    val missing = parts.collectFirst { case MultipartPart.FilePart(_, path, _) if !Files.isReadable(path) => path }
    missing match {
      case Some(path) => Left(org.llm4s.error.ValidationError("multipart", s"unreadable file: $path"))
      case None       => record("POST", url, headers, parts = parts); text
    }
  }

  override def put(
    url: String,
    headers: Map[String, String],
    b: String,
    timeout: FiniteDuration
  ): Result[HttpResponse] = {
    record("PUT", url, headers, b.getBytes("UTF-8")); text
  }

  override def delete(url: String, headers: Map[String, String], timeout: FiniteDuration): Result[HttpResponse] = {
    record("DELETE", url, headers); text
  }

  override def postRaw(
    url: String,
    headers: Map[String, String],
    b: String,
    timeout: FiniteDuration
  ): Result[HttpRawResponse] = {
    record("POST", url, headers, b.getBytes("UTF-8"))
    failure.toLeft(HttpRawResponse(status, body, responseHeaders))
  }

  override def postStream(
    url: String,
    headers: Map[String, String],
    b: String,
    timeout: FiniteDuration
  ): Result[StreamingHttpResponse] = {
    record("POST", url, headers, b.getBytes("UTF-8"))
    failure.toLeft(StreamingHttpResponse(status, new java.io.ByteArrayInputStream(body), responseHeaders))
  }
}

object StubHttpClient {

  def json(status: Int, json: String): StubHttpClient = new StubHttpClient(status, json.getBytes("UTF-8"))

  /** Every byte value, high bit included: invalid as UTF-8, so text decoding corrupts it. */
  val binaryAudio: Array[Byte] = (0 until 256).map(_.toByte).toArray ++ Array[Byte](-1, -2, -128, 0, 127)
}
