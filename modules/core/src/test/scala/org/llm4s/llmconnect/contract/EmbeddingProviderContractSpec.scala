package org.llm4s.llmconnect.contract

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.testutil.MockEmbeddingProviders
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Verifies that the mock EmbeddingProvider implementations
 * satisfy the [[EmbeddingProviderContractBehaviors]] contract.
 */
class EmbeddingProviderContractSpec extends AnyWordSpec with Matchers with EmbeddingProviderContractBehaviors {

  private val testModel = EmbeddingModelConfig(name = "mock-embed", dimensions = 128)

  "SimpleMock EmbeddingProvider" should {
    embeddingProviderContract(
      () => new MockEmbeddingProviders.SimpleMock(dimensions = 128),
      testModel
    )
  }

  "DeterministicMock EmbeddingProvider" should {
    embeddingProviderContract(
      () => new MockEmbeddingProviders.DeterministicMock(dimensions = 128),
      testModel
    )
  }
}
