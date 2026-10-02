package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse, EmbeddingUsage }
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** `OpenAIEmbeddingProvider` against a local server: the request it sends and every way the reply can go. */
class OpenAIEmbeddingProviderHttpSpec extends AnyFlatSpec with Matchers {

  private val request = EmbeddingRequest(Seq("a", "b"), EmbeddingModelConfig("text-embedding-3-small", 2))

  private def provider(baseUrl: String) =
    OpenAIEmbeddingProvider.fromConfig(EmbeddingProviderConfig(baseUrl, "text-embedding-3-small", "key"))

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

  "OpenAIEmbeddingProvider" should "send the inputs and parse the vectors and usage" in {
    val seen = new AtomicReference[(String, ujson.Value)]()
    val reply =
      """{"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}],"usage":{"prompt_tokens":4,"total_tokens":4}}"""
    withServer("/v1/embeddings") { (ex: HttpExchange) =>
      seen.set(
        ex.getRequestHeaders.getFirst("Authorization") ->
          ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      )
      sendJsonResponse(ex, 200, reply)
    } { baseUrl =>
      val response = provider(baseUrl).embed(request)
      response.map(_.embeddings) shouldBe Right(Seq(Vector(0.1, 0.2), Vector(0.3, 0.4)))
      response.map(_.usage) shouldBe Right(Some(EmbeddingUsage(promptTokens = 4, totalTokens = 4)))
    }
    val (auth, body) = seen.get
    auth shouldBe "Bearer key"
    body("input").arr.map(_.str) shouldBe Seq("a", "b")
  }

  it should "leave usage empty when the reply has none" in {
    withServer("/v1/embeddings")(ex => sendJsonResponse(ex, 200, """{"data":[{"embedding":[1.0]}]}""")) { baseUrl =>
      provider(baseUrl).embed(request).map(_.usage) shouldBe Right(None)
    }
  }

  it should "report an unparseable 200 as a parsing error" in {
    withServer("/v1/embeddings")(ex => sendJsonResponse(ex, 200, """{"nope":1}""")) { baseUrl =>
      val error = embeddingError(provider(baseUrl).embed(request))
      error.code shouldBe None
      error.message should startWith("Parsing error")
    }
  }

  it should "report a non-200 with its status and body" in {
    withServer("/v1/embeddings")(ex => sendJsonResponse(ex, 401, """{"error":"bad key"}""")) { baseUrl =>
      val error = embeddingError(provider(baseUrl).embed(request))
      error.code shouldBe Some("401")
      error.message should include("bad key")
    }
  }

  it should "report a refused connection as an EmbeddingError with no status code" in {
    val error = embeddingError(provider(s"http://localhost:${closedPort()}").embed(request))
    error.code shouldBe None
    error.provider shouldBe "openai"
    error.message should startWith("HTTP request failed")
  }

  it should "return a failure for an interrupted caller and keep its interrupt flag" in {
    val p = provider(s"http://localhost:${closedPort()}")
    Thread.currentThread().interrupt()
    val result = p.embed(request)
    Thread.interrupted() shouldBe true
    result.isLeft shouldBe true
  }
}
