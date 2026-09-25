package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.{ GeminiConfig, ProviderConfig, VertexAIConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `LLMConnect` routes Gemini and Vertex AI configs to their clients through the
 * registry. The Gemini case moved from core's `LLMConnectProviderTypeSafetyTest` (#1132).
 */
class GeminiRoutingTest extends AnyFunSuite with Matchers {
  private given ModelRegistryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

  test("Gemini provider with GeminiConfig returns GeminiClient") {
    val cfg: ProviderConfig = GeminiConfig(
      apiKey = "key",
      model = "gemini-2.0-flash",
      baseUrl = "https://example.invalid/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    val res = LLMConnect.getClient(ProviderId("gemini"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "GeminiClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Vertex AI provider with VertexAIConfig returns VertexAIClient") {
    val cfg: ProviderConfig = VertexAIConfig(
      projectId = "my-gcp-project",
      location = "us-central1",
      model = "gemini-2.0-flash",
      credentialFilePath = None,
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    val res = LLMConnect.getClient(ProviderId("vertexai"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "VertexAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("a GeminiConfig routed to the vertexai provider is refused") {
    val cfg: ProviderConfig = GeminiConfig("key", "gemini-2.0-flash", "https://example.invalid/v1beta", 1048576, 8192)
    LLMConnect.getClient(ProviderId("vertexai"), cfg).isLeft shouldBe true
  }
}
