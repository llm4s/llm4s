package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class VoyageAIEmbeddingCredentialEchoSpec extends AnyFlatSpec with Matchers {

  private val echoKey = "echo-key-0123456789abcdef"

  "VoyageAIEmbeddingProvider" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = EmbeddingProviderConfig(baseUrl = baseUrl, model = "voyage-3", apiKey = Secret(echoKey))
      VoyageAIEmbeddingProvider
        .fromConfig(config)
        .embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("voyage-3", 8)))
    }
  }
}
