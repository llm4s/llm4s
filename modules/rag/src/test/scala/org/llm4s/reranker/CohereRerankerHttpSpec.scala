package org.llm4s.reranker

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.CancelledError
import org.llm4s.testkit.LocalProviderTestServer.{ holdOpen, sendJsonResponse, withServer }
import org.llm4s.testkit.ProviderModuleChecks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.llm4s.testutil.EchoedCredentials

/** `CohereReranker` against a local server: the request it sends and every way the reply can go. */
class CohereRerankerHttpSpec extends AnyFlatSpec with Matchers {

  private val request = RerankRequest(query = "scala", documents = Seq("about java", "about scala"), topK = Some(1))

  /** A local port nothing is listening on, so connecting is refused at once. */
  private def closedPort(): Int = {
    val socket = new java.net.ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    port
  }

  private def rerankError(result: org.llm4s.types.Result[RerankResponse]): RerankError = result match {
    case Left(e: RerankError) => e
    case other                => fail(s"expected a RerankError, got $other")
  }

  "CohereReranker" should "send the query and parse the ranked results" in {
    val seen = new AtomicReference[(String, ujson.Value)]()
    val reply =
      """{"results":[{"index":1,"relevance_score":0.9,"document":{"text":"about scala"}}]}"""
    withServer("/v1/rerank") { (ex: HttpExchange) =>
      seen.set(
        ex.getRequestHeaders.getFirst("Authorization") ->
          ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      )
      sendJsonResponse(ex, 200, reply)
    } { baseUrl =>
      val response = CohereReranker(apiKey = "key", model = "rerank-v3", baseUrl = baseUrl).rerank(request)
      response.map(_.results) shouldBe Right(Seq(RerankResult(index = 1, score = 0.9, document = "about scala")))
      response.map(_.metadata("top_n")) shouldBe Right("1")
    }
    val (auth, body) = seen.get
    auth shouldBe "Bearer key"
    body("model").str shouldBe "rerank-v3"
    body("top_n").num shouldBe 1
  }

  it should "take the document text from the request when not returning documents" in {
    withServer("/v1/rerank")(ex => sendJsonResponse(ex, 200, """{"results":[{"index":0,"relevance_score":0.4}]}""")) {
      baseUrl =>
        CohereReranker(apiKey = "k", baseUrl = baseUrl)
          .rerank(request.copy(returnDocuments = false))
          .map(_.results.map(_.document)) shouldBe Right(Seq("about java"))
    }
  }

  it should "report an unparseable 200 as a parsing error" in {
    withServer("/v1/rerank")(ex => sendJsonResponse(ex, 200, """{"unexpected":true}""")) { baseUrl =>
      val error = rerankError(CohereReranker(apiKey = "k", baseUrl = baseUrl).rerank(request))
      error.code shouldBe None
      error.message should startWith("Parsing error")
    }
  }

  it should "report a non-200 with its status and body" in {
    withServer("/v1/rerank")(ex => sendJsonResponse(ex, 429, """{"message":"slow down"}""")) { baseUrl =>
      val error = rerankError(CohereReranker(apiKey = "k", baseUrl = baseUrl).rerank(request))
      error.code shouldBe Some("429")
      error.message should include("slow down")
    }
  }

  it should "report a refused connection as a RerankError with no status code" in {
    val error = rerankError(CohereReranker(apiKey = "k", baseUrl = s"http://localhost:${closedPort()}").rerank(request))
    error.code shouldBe None
    error.message should startWith("HTTP request failed")
  }

  it should "return a failure for an interrupted caller and keep its interrupt flag" in {
    val reranker = CohereReranker(apiKey = "k", baseUrl = s"http://localhost:${closedPort()}")
    Thread.currentThread().interrupt()
    val result = reranker.rerank(request)
    Thread.interrupted() shouldBe true
    result.left.toOption.get shouldBe a[CancelledError]
  }

  it should "return CancelledError, with the interrupt flag set, when its thread is interrupted mid-request" in {
    withServer("/v1/rerank")(holdOpen) { baseUrl =>
      val reranker = CohereReranker(apiKey = "k", baseUrl = baseUrl)
      ProviderModuleChecks.assertCallCancelsWhenInterrupted("rerank")(reranker.rerank(request))
    }
  }

  "CohereReranker" should "redact credentials echoed in an error body before truncating it, in its error and its log (#1674)" in {
    Seq(EchoedCredentials.Text, EchoedCredentials.JsonError).foreach { reply =>
      withServer("/v1/rerank")(ex => sendJsonResponse(ex, 500, reply)) { baseUrl =>
        val (result, lines) = EchoedCredentials.logged(CohereReranker(apiKey = "k", baseUrl = baseUrl).rerank(request))
        val error           = rerankError(result)
        error.message should include("[REDACTED]")
        EchoedCredentials.leaked(error.message) shouldBe empty
        lines.flatMap(EchoedCredentials.leaked) shouldBe empty
      }
    }
  }
}
