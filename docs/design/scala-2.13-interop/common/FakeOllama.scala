import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import com.sun.net.httpserver.{HttpExchange, HttpServer}

/**
 * A local stand-in for Ollama's `/api/chat`. Request `n` gets reply `n` of the script (the last one repeats), as one
 * JSON object (`"stream": false`) or, for a text reply, as two newline-delimited chunks (`"stream": true`). Written
 * without llm4s or ujson types, so it compiles whatever the probes find.
 */
object FakeOllama {

  sealed trait Reply
  final case class Text(content: String)                       extends Reply
  final case class ToolCall(name: String, argumentsJson: String) extends Reply

  def withServer[A](reply: String)(body: Int => A): A = withReplies(Seq(Text(reply)))(body)

  def withReplies[A](replies: Seq[Reply])(body: Int => A): A = {
    val requests = new AtomicInteger(0)
    val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val request = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val stream  = request.contains("\"stream\":true")
        val reply   = replies(math.min(requests.getAndIncrement(), replies.size - 1))
        def line(message: String, done: Boolean) =
          s"""{"model":"llama3","created_at":"2026-10-21T00:00:00Z","message":$message,"done":$done""" +
            (if (done) ""","done_reason":"stop","prompt_eval_count":9,"eval_count":14}""" else "}")
        def text(content: String) =
          s"""{"role":"assistant","content":"${content.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        val payload = reply match {
          case Text(content) if stream =>
            line(text(content.take(content.length / 2)), done = false) + "\n" +
              line(text(content.drop(content.length / 2)), done = true) + "\n"
          case Text(content) => line(text(content), done = true)
          case ToolCall(name, arguments) =>
            val message =
              s"""{"role":"assistant","content":"","tool_calls":[{"function":{"name":"$name","arguments":$arguments}}]}"""
            line(message, done = true) + (if (stream) "\n" else "")
        }
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
