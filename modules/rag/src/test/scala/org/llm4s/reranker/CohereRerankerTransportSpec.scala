package org.llm4s.reranker

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Transport failures reach the caller as a `RerankError`, never an exception. */
class CohereRerankerTransportSpec extends AnyFlatSpec with Matchers {

  /** A local port nothing is listening on, so connecting is refused at once. */
  private def closedPort(): Int = {
    val socket = new java.net.ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    port
  }

  private val request = RerankRequest(query = "q", documents = Seq("a", "b"))

  "CohereReranker" should "report a refused connection as a RerankError with no status code" in {
    val reranker = CohereReranker(apiKey = "k", baseUrl = s"http://localhost:${closedPort()}")
    val error    = reranker.rerank(request).left.getOrElse(fail("expected a failure"))
    error shouldBe a[RerankError]
    error.asInstanceOf[RerankError].code shouldBe None
    error.message should startWith("HTTP request failed")
  }

  it should "return a failure for an interrupted caller and keep its interrupt flag" in {
    val reranker = CohereReranker(apiKey = "k", baseUrl = s"http://localhost:${closedPort()}")
    Thread.currentThread().interrupt()
    val result = reranker.rerank(request)
    Thread.interrupted() shouldBe true
    result.isLeft shouldBe true
  }
}
