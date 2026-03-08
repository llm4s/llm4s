package org.llm4s.llmconnect.contract

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Reusable contract behaviour suite for [[EmbeddingProvider]] implementations.
 *
 * Mix this trait into an `AnyWordSpec with Matchers` and call
 * `behave like embeddingProviderContract(factory, testModel)` for each implementation.
 * The factory is invoked per-test so each test gets a fresh instance.
 */
trait EmbeddingProviderContractBehaviors { this: AnyWordSpec with Matchers =>

  def embeddingProviderContract(
    createProvider: () => EmbeddingProvider,
    testModel: EmbeddingModelConfig
  ): Unit = {

    // -- embed() guarantees ---------------------------------------------------

    "return Right for a valid single-text request" in {
      val provider = createProvider()
      val request  = EmbeddingRequest(input = Seq("Hello world"), model = testModel)
      val result   = provider.embed(request)
      result.isRight shouldBe true
    }

    "return one embedding vector per input text" in {
      val provider = createProvider()
      val request  = EmbeddingRequest(input = Seq("Hello world"), model = testModel)
      val result   = provider.embed(request)
      result.foreach(response => response.embeddings.size shouldBe request.input.size)
    }

    "return Right for a multi-text batch request" in {
      val provider = createProvider()
      val inputs   = Seq("one", "two", "three")
      val request  = EmbeddingRequest(input = inputs, model = testModel)
      val result   = provider.embed(request)
      result.isRight shouldBe true
      result.foreach(response => response.embeddings.size shouldBe inputs.size)
    }

    "return embedding vectors with consistent dimensionality" in {
      val provider = createProvider()
      val inputs   = Seq("alpha", "beta", "gamma")
      val request  = EmbeddingRequest(input = inputs, model = testModel)
      val result   = provider.embed(request)
      result.foreach { response =>
        val dims = response.embeddings.map(_.size).distinct
        dims.size shouldBe 1
      }
    }

    "return non-empty embedding vectors" in {
      val provider = createProvider()
      val request  = EmbeddingRequest(input = Seq("test"), model = testModel)
      val result   = provider.embed(request)
      result.foreach(response => response.embeddings.foreach(v => v should not be empty))
    }

    // -- Error handling -------------------------------------------------------

    "not throw on failure — returns Left instead" in {
      val provider = createProvider()
      val request  = EmbeddingRequest(input = Seq("test"), model = testModel)
      noException should be thrownBy provider.embed(request)
    }

    // -- Edge cases -----------------------------------------------------------

    "handle empty input list without throwing" in {
      val provider = createProvider()
      val request  = EmbeddingRequest(input = Seq.empty, model = testModel)
      noException should be thrownBy provider.embed(request)
    }

    "handle single-character input without error" in {
      val provider = createProvider()
      val request  = EmbeddingRequest(input = Seq("a"), model = testModel)
      val result   = provider.embed(request)
      result.isRight shouldBe true
    }
  }
}
