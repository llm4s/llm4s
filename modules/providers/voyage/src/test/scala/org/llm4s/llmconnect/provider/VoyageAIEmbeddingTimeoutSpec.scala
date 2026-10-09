package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * The `timeouts.request` of an embedding section reaches VoyageAIEmbeddingProvider's HTTP call (#712); without one the
 * call keeps its two-minute default.
 */
class VoyageAIEmbeddingTimeoutSpec extends AnyFlatSpec with Matchers {

  private def config(timeouts: ProviderTimeouts): EmbeddingProviderConfig =
    EmbeddingProviderConfig(
      baseUrl = "http://localhost:1",
      model = "voyage-3",
      apiKey = "test-key",
      timeouts = timeouts
    )

  private def request: EmbeddingRequest = EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("voyage-3", 3))

  /** The timeout the provider passed to the HTTP client for one `embed`. */
  private def timeoutUsed(timeouts: ProviderTimeouts): Option[FiniteDuration] = {
    val http = new MockHttpClient(HttpResponse(200, """{"data":[{"embedding":[0.1,0.2,0.3]}]}""", Map.empty))
    VoyageAIEmbeddingProvider.forTest(config(timeouts), http).embed(request)
    http.lastTimeout
  }

  "VoyageAIEmbeddingProvider" should "send with the configured request timeout" in {
    timeoutUsed(ProviderTimeouts(request = Some(7.seconds))) shouldBe Some(7.seconds)
  }

  it should "keep its two-minute default when the section sets no timeout" in {
    timeoutUsed(ProviderTimeouts.default) shouldBe Some(120.seconds)
  }

  it should "ignore the stream timeout, which an embedding call has no use for" in {
    timeoutUsed(ProviderTimeouts(stream = Some(9.seconds))) shouldBe Some(120.seconds)
  }
}
