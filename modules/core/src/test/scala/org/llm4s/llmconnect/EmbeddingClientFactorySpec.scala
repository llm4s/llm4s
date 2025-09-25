package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmbeddingClientFactorySpec extends AnyWordSpec with Matchers {

  "EmbeddingClient.from(provider,cfg)" should {
    "build client for openai without throwing" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "https://api.openai.com/v1",
        model = "text-embedding-3-small",
        apiKey = "sk-test"
      )
      val res = EmbeddingClient.from("openai", cfg)
      res.isRight shouldBe true
    }

    "build client for voyage without throwing" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "https://api.voyage.ai",
        model = "voyage-3",
        apiKey = "vk-test"
      )
      val res = EmbeddingClient.from("voyage", cfg)
      res.isRight shouldBe true
    }

    "reject unknown provider" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "http://localhost",
        model = "m",
        apiKey = "k"
      )
      val res = EmbeddingClient.from("unknown", cfg)
      res.isLeft shouldBe true
    }
  }
}
