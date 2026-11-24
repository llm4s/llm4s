package org.llm4s.speech

import org.llm4s.error.LLMError
import org.llm4s.http._
import org.llm4s.types.Result

import java.nio.file.{ Files, Path }
import scala.collection.mutable

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
    val files = parts.collect { case MultipartPart.FilePart(n, MultipartPart.Content.File(path), _, _) => n -> path }
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

  override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
    val method = request.method.name
    val unreadable = request.body match {
      case HttpBody.Text(text)       => record(method, request.url, request.headers, text.getBytes("UTF-8")); None
      case HttpBody.Bytes(bytes)     => record(method, request.url, request.headers, bytes); None
      case HttpBody.Empty            => record(method, request.url, request.headers); None
      case HttpBody.Multipart(parts) =>
        // As the JDK client does, an unreadable file part is a ValidationError, not an exception.
        val missing = parts.collectFirst {
          case MultipartPart.FilePart(_, MultipartPart.Content.File(path), _, _) if !Files.isReadable(path) => path
        }
        if (missing.isEmpty) record(method, request.url, request.headers, parts = parts)
        missing.map(path => org.llm4s.error.ValidationError("multipart", s"unreadable file: $path"))
    }
    unreadable
      .orElse(failure)
      .toLeft(StreamingHttpResponse(status, new java.io.ByteArrayInputStream(body), responseHeaders))
  }
}

object StubHttpClient {

  def json(status: Int, json: String): StubHttpClient = new StubHttpClient(status, json.getBytes("UTF-8"))

  /** Every byte value, high bit included: invalid as UTF-8, so text decoding corrupts it. */
  val binaryAudio: Array[Byte] = (0 until 256).map(_.toByte).toArray ++ Array[Byte](-1, -2, -128, 0, 127)
}
