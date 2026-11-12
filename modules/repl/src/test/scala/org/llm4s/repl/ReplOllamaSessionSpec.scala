package org.llm4s.repl

import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** `Session.ollama` against a local HTTP server that answers Ollama's `/api/chat`, with `llm4s-ollama` on the classpath. */
class ReplOllamaSessionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val requestBody = new AtomicReference[String]("")

  private val server: HttpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
  server.createContext(
    "/api/chat",
    exchange => {
      requestBody.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      val out = Seq(
        """{"model":"llama3","message":{"role":"assistant","content":"Hello"},"done":false}""",
        """{"model":"llama3","message":{"role":"assistant","content":", Scala."},"done":false}""",
        """{"model":"llama3","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":4,"eval_count":3}"""
      ).mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/x-ndjson")
      exchange.sendResponseHeaders(200, out.length.toLong)
      exchange.getResponseBody.write(out)
      exchange.close()
    }
  )
  server.start()

  override def afterAll(): Unit = server.stop(0)

  private def baseUrl = s"http://localhost:${server.getAddress.getPort}"

  "Session.ollama" should "build a session on the Ollama provider with no application.conf section" in {
    val session = Session.ollama("llama3")
    session.failure shouldBe None
    session.description shouldBe "ollama, llama3"
    session.toString shouldBe "Session(ollama, llama3, 0 messages)"
    session.close()
  }

  it should "send ask to the server at baseUrl and stream the reply" in {
    val buffer    = new ByteArrayOutputStream()
    val session   = Session.ollama("llama3", baseUrl).withOutput(new PrintStream(buffer, true, "UTF-8"))
    given Session = session
    ask("Say hello").toString shouldBe "Hello, Scala."
    buffer.toString(StandardCharsets.UTF_8).linesIterator.toList.head shouldBe "Hello, Scala."
    ujson.read(requestBody.get)("model").str shouldBe "llama3"
    history.messages.map(_.content) shouldBe Seq("Say hello", "Hello, Scala.")
    session.close()
  }

  it should "print the error, not a stack trace, when llm4s-ollama is not on the classpath" in {
    val buffer = new ByteArrayOutputStream()
    val session =
      Session.ollama("llama3", None)(using ProviderRegistry.of()).withOutput(new PrintStream(buffer, true, "UTF-8"))
    session.failure should not be empty
    given Session = session
    ask("Say hello") shouldBe a[Outcome.Failed[?]]
    val printed = buffer.toString(StandardCharsets.UTF_8)
    printed should startWith("error: ")
    printed should include("ollama")
    (printed should not).include("\tat ")
  }
}
