package org.llm4s.mcp

import java.io.InputStream
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import scala.concurrent.duration._
import scala.util.Try

/**
 * An open SSE GET against a test server, whose stream a test can close while another thread is
 * blocked reading it.
 *
 * The specs used to read SSE through `HttpURLConnection` on a background thread and call
 * `disconnect()` when done. `disconnect()` closes the response stream, and that close waits for the
 * stream's lock, which the blocked reader holds until its read timeout fires: every SSE test sat out
 * the full read timeout (10 s, or 5 s) after its assertions had passed. Closing the JDK `HttpClient`'s
 * response stream instead cancels it at once and wakes the reader.
 */
final private[mcp] class SseTestStream private (client: HttpClient, response: HttpResponse[InputStream])
    extends AutoCloseable {

  def statusCode: Int = response.statusCode()

  def body: InputStream = response.body()

  override def close(): Unit = {
    Try(response.body().close())
    client.shutdownNow()
  }
}

private[mcp] object SseTestStream {

  /** Sends `GET http://127.0.0.1:<port><path>` with `Accept: text/event-stream`; returns once headers arrive. */
  def open(
    port: Int,
    path: String,
    headers: Map[String, String] = Map.empty,
    timeout: FiniteDuration = 5.seconds
  ): SseTestStream = {
    val client = HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1) // the JDK HttpServer under test speaks HTTP/1.1 only
      .connectTimeout(java.time.Duration.ofMillis(timeout.toMillis))
      .build()
    val request = headers
      .foldLeft(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
          .timeout(java.time.Duration.ofMillis(timeout.toMillis))
          .header("Accept", "text/event-stream")
      ) { case (builder, (name, value)) => builder.header(name, value) }
      .GET()
      .build()
    Try(client.send(request, HttpResponse.BodyHandlers.ofInputStream())).fold(
      error => { client.shutdownNow(); throw error },
      response => new SseTestStream(client, response)
    )
  }
}
