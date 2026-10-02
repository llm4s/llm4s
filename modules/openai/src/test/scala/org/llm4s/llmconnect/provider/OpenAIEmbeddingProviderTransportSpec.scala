package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Transport failures reach the caller as an `EmbeddingError`, never an exception. */
class OpenAIEmbeddingProviderTransportSpec extends AnyFlatSpec with Matchers {

  /** A local port nothing is listening on, so connecting is refused at once. */
  private def closedPort(): Int = {
    val socket = new java.net.ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    port
  }

  private def provider() =
    OpenAIEmbeddingProvider.fromConfig(EmbeddingProviderConfig(s"http://localhost:${closedPort()}", "m", "k"))

  private val request = EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("m", 3))

  "OpenAIEmbeddingProvider" should "report a refused connection as an EmbeddingError with no status code" in {
    val error = provider().embed(request) match {
      case Left(e: EmbeddingError) => e
      case other                   => fail(s"expected an EmbeddingError, got $other")
    }
    error.code shouldBe None
    error.provider shouldBe "openai"
    error.message should startWith("HTTP request failed")
  }

  it should "return a failure for an interrupted caller and keep its interrupt flag" in {
    val p = provider()
    Thread.currentThread().interrupt()
    val result = p.embed(request)
    Thread.interrupted() shouldBe true
    result.isLeft shouldBe true
  }
}
