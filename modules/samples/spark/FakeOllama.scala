import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import com.sun.net.httpserver.{HttpExchange, HttpServer}

/**
 * A local stand-in for Ollama's `/api/chat`, so the job runs with no model and no key. It answers with the last
 * line of the user's message in upper case, and fails with HTTP 500 for a message that contains "boom", so the
 * job's error path is exercised too.
 */
object FakeOllama {

  private val LastUserContent = "(?s).*\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}\\].*".r

  def start(): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val request = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val prompt  = request match {
          case LastUserContent(content) => content.split("\\\\n").last
          case _                        => ""
        }
        val (status, body) =
          if (prompt.contains("boom")) (500, """{"error":"the fake model failed"}""")
          else
            200 -> s"""{"model":"llama3","created_at":"2026-10-21T00:00:00Z","message":{"role":"assistant","content":"${prompt.toUpperCase}"},"done":true,"done_reason":"stop","prompt_eval_count":9,"eval_count":5}"""
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    server
  }
}
