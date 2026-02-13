package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.provider.{ CohereClient, LLMProvider }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMConnectSpec extends AnyFlatSpec with Matchers {

  "LLMConnect" should "build Cohere client from provider and config" in {
    val config = CohereConfig.fromValues(
      modelName = "command-r",
      apiKey = "test-key",
      baseUrl = "https://api.cohere.ai"
    )

    val result = LLMConnect.getClient(LLMProvider.Cohere, config)

    result.isRight shouldBe true
    result.toOption.get shouldBe a[CohereClient]
  }
}
