package org.llm4s.testutil

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Shared local HTTP server utility for provider integration tests.
 *
 * Spins up an ephemeral-port HttpServer, runs the test, and tears down automatically.
 * Extracts patterns previously duplicated across OpenRouterClientSpec, MistralClientSpec,
 * and CohereClientSpec.
 */
object LocalProviderTestServer {

  /**
   * Starts a local HTTP server with a handler bound to the given path,
   * runs the test block with the server's base URL, then stops the server.
   */
  def withServer(path: String)(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(path, exchange => handler(exchange))
    server.start()

    val baseUrl = s"http://localhost:${server.getAddress.getPort}"

    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  /** Sends a JSON response with the given status code and body. */
  def sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  /** Sends an SSE (text/event-stream) response with the given raw body. */
  def sendSseResponse(exchange: HttpExchange, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  /** Builds a standard OpenAI-format completion JSON response. */
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

  /** Builds SSE data lines from a sequence of content chunks, ending with [DONE]. */
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
