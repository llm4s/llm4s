import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import com.sun.net.httpserver.{HttpExchange, HttpServer}

/**
 * A local stand-in for Ollama's `/api/chat`: every request gets the same assistant reply, as one JSON object
 * (`"stream": false`) or as two newline-delimited chunks (`"stream": true`). Written without llm4s or ujson
 * types, so it compiles whatever the probes find.
 */
object FakeOllama {

  def withServer[A](reply: String)(body: Int => A): A = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val request = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val stream  = request.contains("\"stream\":true")
        val text    = reply.replace("\\", "\\\\").replace("\"", "\\\"")
        def chunk(content: String, done: Boolean) =
          s"""{"model":"llama3","created_at":"2026-10-21T00:00:00Z","message":{"role":"assistant","content":"$content"},"done":$done""" +
            (if (done) ""","done_reason":"stop","prompt_eval_count":9,"eval_count":14}""" else "}")
        val payload =
          if (stream) chunk(text.take(text.length / 2), done = false) + "\n" + chunk(text.drop(text.length / 2), done = true) + "\n"
          else chunk(text, done = true)
        val bytes = payload.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", if (stream) "application/x-ndjson" else "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try body(server.getAddress.getPort)
    finally server.stop(0)
  }

  /** Points `llm4s.providers` at the fake server, as an application.conf section would. */
  def configure(port: Int): Unit = {
    System.setProperty("llm4s.providers.provider", "local")
    System.setProperty("llm4s.providers.local.provider", "ollama")
    System.setProperty("llm4s.providers.local.model", "llama3")
    System.setProperty("llm4s.providers.local.baseUrl", s"http://localhost:$port")
    com.typesafe.config.ConfigFactory.invalidateCaches()
  }
}
