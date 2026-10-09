package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import org.llm4s.testutil.EchoedCredentials

/** `OllamaEmbeddingProvider` against a local server: the requests it sends and every way the reply can go. */
class OllamaEmbeddingProviderHttpSpec extends AnyFlatSpec with Matchers {

  private val request = EmbeddingRequest(Seq("first", "second"), EmbeddingModelConfig("nomic-embed-text", 2))

  private def provider(baseUrl: String, apiKey: String = "not-required") =
    OllamaEmbeddingProvider.fromConfig(EmbeddingProviderConfig(baseUrl, "nomic-embed-text", apiKey))

  /** A local port nothing is listening on, so connecting is refused at once. */
  private def closedPort(): Int = {
    val socket = new java.net.ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    port
  }

  private def embeddingError(result: Either[?, EmbeddingResponse]): EmbeddingError = result match {
    case Left(e: EmbeddingError) => e
    case other                   => fail(s"expected an EmbeddingError, got $other")
  }

  "OllamaEmbeddingProvider" should "embed each input with its own request, without auth by default" in {
    val seen = new ConcurrentLinkedQueue[(Option[String], String)]()
    withServer("/api/embeddings") { (ex: HttpExchange) =>
      val prompt = ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))("prompt").str
      seen.add(Option(ex.getRequestHeaders.getFirst("Authorization")) -> prompt)
      sendJsonResponse(ex, 200, s"""{"embedding":[${prompt.length}.0, 1.0]}""")
    } { baseUrl =>
      provider(baseUrl).embed(request).map(_.embeddings) shouldBe Right(Seq(Vector(5.0, 1.0), Vector(6.0, 1.0)))
    }
    seen.asScala.toList shouldBe List(None -> "first", None -> "second")
  }

  it should "send a configured API key as a bearer token" in {
    val auth = new ConcurrentLinkedQueue[String]()
    withServer("/api/embeddings") { (ex: HttpExchange) =>
      auth.add(ex.getRequestHeaders.getFirst("Authorization"))
      sendJsonResponse(ex, 200, """{"embedding":[1.0]}""")
    }(baseUrl => provider(baseUrl, apiKey = "secret").embed(request).isRight shouldBe true)
    auth.asScala.toSet shouldBe Set("Bearer secret")
  }

  it should "report an unparseable 200 as a parsing error" in {
    withServer("/api/embeddings")(ex => sendJsonResponse(ex, 200, """{"nope":1}""")) { baseUrl =>
      val error = embeddingError(provider(baseUrl).embed(request))
      error.code shouldBe None
      error.message should startWith("Parsing error")
    }
  }

  it should "report a non-200 with its status and body" in {
    withServer("/api/embeddings")(ex => sendJsonResponse(ex, 404, """{"error":"model not found"}""")) { baseUrl =>
      val error = embeddingError(provider(baseUrl).embed(request))
      error.code shouldBe Some("404")
      error.message should include("model not found")
    }
  }

  it should "report a refused connection as an EmbeddingError with no status code" in {
    val error = embeddingError(provider(s"http://localhost:${closedPort()}").embed(request))
    error.code shouldBe None
    error.provider shouldBe "ollama"
    error.message should startWith("HTTP request failed")
  }

  it should "return a failure for an interrupted caller and keep its interrupt flag" in {
    val p = provider(s"http://localhost:${closedPort()}")
    Thread.currentThread().interrupt()
    val result = p.embed(request)
    Thread.interrupted() shouldBe true
    result.isLeft shouldBe true
  }

  "OllamaEmbeddingProvider" should "redact credentials echoed in an error body before truncating it, in its error and its log (#1674)" in {
    Seq(EchoedCredentials.Text, EchoedCredentials.JsonError).foreach { reply =>
      withServer("/api/embeddings")(ex => sendJsonResponse(ex, 500, reply)) { baseUrl =>
        val (result, lines) = EchoedCredentials.logged(provider(baseUrl).embed(request))
        val error           = embeddingError(result)
        error.message should include("[REDACTED]")
        EchoedCredentials.leaked(error.message) shouldBe empty
        val errorLines = lines.filter(_.contains("[OllamaEmbeddingProvider] HTTP error"))
        errorLines should not be empty
        errorLines.foreach(_ should include("[REDACTED]"))
        lines.flatMap(EchoedCredentials.leaked) shouldBe empty
      }
    }
  }
}
