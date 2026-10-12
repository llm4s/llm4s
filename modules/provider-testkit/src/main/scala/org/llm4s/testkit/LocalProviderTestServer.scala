package org.llm4s.testkit

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, Executors }
import scala.util.Try

/**
 * A local HTTP server to point a provider client at, so a test exercises the client's real
 * request and response handling - including streaming - without a network or an API key.
 *
 * It is the JDK's `com.sun.net.httpserver.HttpServer` on an ephemeral port, so it adds no
 * dependency.
 *
 * {{{
 * withServer("/v1/chat")(exchange => sendSseResponse(exchange, body)) { baseUrl =>
 *   val client = assertBuildsClient(AcmeProvider, section.copy(baseUrl = Some(BaseUrl(baseUrl))))
 *   assertStreams(client)
 * }
 * }}}
 *
 * [[holdOpen]] and [[streamThenHold]] are handlers that never finish answering, for checking that
 * a client's call can be interrupted (see `ProviderModuleChecks.assertCancelsWhenInterrupted`).
 * Handlers run on virtual threads, so this needs JDK 21 (see `docs/guide/writing-a-provider.md`).
 */
object LocalProviderTestServer {

  private val ReleaseAttribute = "llm4s.testkit.release"

  /**
   * Starts a local HTTP server with `handler` bound to `path`, runs `test` with the server's
   * base URL (`http://localhost:<port>`, no trailing slash), then stops the server - also when
   * `test` throws, whose exception is then rethrown.
   */
  def withServer(path: String)(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    val handlers = Executors.newVirtualThreadPerTaskExecutor()
    val release  = new CountDownLatch(1)
    val context  = server.createContext(path, exchange => handler(exchange))
    context.getAttributes.put(ReleaseAttribute, release)
    server.setExecutor(handlers)
    server.start()

    val outcome = Try(test(s"http://localhost:${server.getAddress.getPort}"))
    release.countDown() // let held handlers finish, so stop() does not wait on them
    server.stop(0)
    handlers.shutdownNow(): Unit
    outcome.fold(error => throw error, _ => ())
  }

  /**
   * Holds the request open without answering until the enclosing [[withServer]] block ends - a
   * provider that never replies, for checking that a client's call can be interrupted.
   */
  def holdOpen(exchange: HttpExchange): Unit =
    exchange.getHttpContext.getAttributes.get(ReleaseAttribute) match {
      case latch: CountDownLatch => latch.await()
      case _                     => ()
    }

  /**
   * Starts a 200 streaming response, sends `firstEvent` (one complete event in the provider's
   * stream format), then holds the response open like [[holdOpen]].
   */
  def streamThenHold(exchange: HttpExchange, firstEvent: String, contentType: String = "text/event-stream"): Unit = {
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(200, 0) // 0: chunked, length unknown
    val os = exchange.getResponseBody
    os.write(firstEvent.getBytes(StandardCharsets.UTF_8))
    os.flush()
    holdOpen(exchange)
    Try(os.close()): Unit
  }

  /** Sends a JSON response with the given status code and body. */
  def sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: String): Unit =
    send(exchange, statusCode, "application/json", body)

  /** Sends a 200 SSE (`text/event-stream`) response with the given raw body. */
  def sendSseResponse(exchange: HttpExchange, body: String): Unit =
    send(exchange, 200, "text/event-stream", body)

  private def send(exchange: HttpExchange, statusCode: Int, contentType: String, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(statusCode, bytes.length.toLong)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  /** A minimal OpenAI-format (`/chat/completions`) completion response carrying `content`. */
  def openAICompletion(content: String, model: String = "test-model"): String =
    s"""{
       |  "id": "chatcmpl-test",
       |  "object": "chat.completion",
       |  "created": 1700000000,
       |  "model": "$model",
       |  "choices": [{
       |    "index": 0,
       |    "message": {
       |      "role": "assistant",
       |      "content": ${ujson.Str(content).render()}
       |    },
       |    "finish_reason": "stop"
       |  }],
       |  "usage": {
       |    "prompt_tokens": 10,
       |    "completion_tokens": 5,
       |    "total_tokens": 15
       |  }
       |}""".stripMargin

  /** A minimal Anthropic Messages (`/v1/messages`) response carrying `content` as one text block. */
  def anthropicMessage(content: String, model: String = "claude-test"): String =
    s"""{
       |  "id": "msg_test",
       |  "type": "message",
       |  "role": "assistant",
       |  "model": "$model",
       |  "content": [{"type": "text", "text": ${ujson.Str(content).render()}}],
       |  "stop_reason": "end_turn",
       |  "stop_sequence": null,
       |  "usage": {"input_tokens": 10, "output_tokens": 5}
       |}""".stripMargin

  /**
   * An OpenAI-format streaming body: one SSE `data:` event per chunk of content, the last
   * carrying `finish_reason` and usage, then `data: [DONE]`.
   */
  def openAISseBody(chunks: Seq[String], model: String = "test-model"): String = {
    val dataLines = chunks.zipWithIndex.map { case (text, i) =>
      val isLast       = i == chunks.size - 1
      val finishReason = if (isLast) """"stop"""" else "null"
      val usagePart =
        if (isLast) ""","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}"""
        else ""
      s"""data: {"id":"chatcmpl-test","created":0,"model":"$model","choices":[{"index":0,"delta":{"content":${ujson
          .Str(text)
          .render()}},"finish_reason":$finishReason}]$usagePart}"""
    }
    (dataLines :+ "data: [DONE]").mkString("\n\n") + "\n\n"
  }
}
