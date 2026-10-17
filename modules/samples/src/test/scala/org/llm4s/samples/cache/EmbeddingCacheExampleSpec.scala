package org.llm4s.samples.cache

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EmbeddingCacheExampleSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val model = EmbeddingModelConfig("stub-embedding", 2)

  /** One vector per text, derived from its length, so the same text always gives the same vector. */
  private object StubProvider extends EmbeddingProvider {
    def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(request.input.map(t => Seq(t.length.toDouble, 1.0))))
  }

  "EmbeddingCacheExample.run" should "serve the second call for the same text from the cache" in {
    val report = EmbeddingCacheExample.run(StubProvider, model).toOption.get

    val Seq(first, second, _) = report.steps
    first.hit shouldBe false
    first.providerCalls shouldBe 1
    second.hit shouldBe true
    second.providerCalls shouldBe 0
    second.providerTexts shouldBe 0
  }

  it should "send only the texts that are not cached when a call mixes both" in {
    val report = EmbeddingCacheExample.run(StubProvider, model).toOption.get

    val third = report.steps(2)
    third.hit shouldBe false
    third.providerCalls shouldBe 1
    third.providerTexts shouldBe 1
  }

  it should "report the hits and misses of the cache itself" in {
    val stats = EmbeddingCacheExample.run(StubProvider, model).toOption.get.stats

    stats.hits shouldBe 2   // "hello world" on calls 2 and 3
    stats.misses shouldBe 2 // "hello world" on call 1, "something new" on call 3
    stats.size shouldBe 2
  }

  it should "fail when the provider fails" in {
    val failing = new EmbeddingProvider {
      def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        Left(EmbeddingError(code = None, message = "down", provider = "stub"))
    }

    EmbeddingCacheExample.run(failing, model).isLeft shouldBe true
  }
}
