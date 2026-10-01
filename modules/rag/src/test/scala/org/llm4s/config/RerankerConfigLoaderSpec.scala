package org.llm4s.config

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.reranker.{ CohereReranker, RerankProviderConfig }
import org.llm4s.testkit.CredentialsRoundTrip
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The reranker's config, and its share of Cohere's key.
 *
 * Every `reference.conf` on `llm4s-rag`'s test classpath is loaded - its own, which binds
 * `RERANK_PROVIDER` and `COHERE_API_KEY`, and `llm4s-openai-compatible`'s, which binds the Cohere
 * chat provider's key to the same `llm4s.credentials.cohere.apiKey` - with an injected
 * environment and the real one switched off.
 */
class RerankerConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues:

  private val cohereSelected = Map("RERANK_PROVIDER" -> "cohere")

  "RerankerConfigLoader" should {

    "select no reranker when RERANK_PROVIDER is unset or none" in {
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", Map.empty)).value shouldBe None
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", Map("RERANK_PROVIDER" -> "none"))).value shouldBe None
    }

    "take the Cohere key from COHERE_API_KEY, through llm4s.credentials.cohere.apiKey" in {
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", cohereSelected + ("COHERE_API_KEY" -> "co-shared")))
        .value shouldBe Some(
        RerankProviderConfig(CohereReranker.DEFAULT_BASE_URL, "co-shared", CohereReranker.DEFAULT_MODEL)
      )
    }

    "prefer a key set under llm4s.rerank.cohere, the reranker's own" in {
      val hocon = """llm4s.rerank.cohere.apiKey = "co-reranker-account""""
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv(hocon, cohereSelected + ("COHERE_API_KEY" -> "co-shared")))
        .value
        .map(_.apiKey) shouldBe Some("co-reranker-account")
    }

    "read the model and base URL variables" in {
      val env = cohereSelected ++ Map(
        "COHERE_API_KEY"         -> "co-shared",
        "COHERE_RERANK_MODEL"    -> "rerank-v3.5",
        "COHERE_RERANK_BASE_URL" -> "https://cohere.example"
      )
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", env)).value shouldBe Some(
        RerankProviderConfig("https://cohere.example", "co-shared", "rerank-v3.5")
      )
    }

    "fail naming COHERE_API_KEY and the reranker's block when no key is set" in {
      RerankerConfigLoader.load(ReferenceConfig.withEnv("", cohereSelected)).left.value.message shouldBe
        "Missing Cohere reranker apiKey: set COHERE_API_KEY, or set apiKey under llm4s.rerank.cohere in application.conf"
    }

    "refuse llm, which needs a client config cannot supply, and an unknown provider" in {
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", Map("RERANK_PROVIDER" -> "llm")))
        .left
        .value
        .message should include("RerankerFactory.llm")
      RerankerConfigLoader
        .load(ReferenceConfig.withEnv("", Map("RERANK_PROVIDER" -> "acme")))
        .left
        .value
        .message should include("Unknown llm4s.rerank.provider 'acme'")
    }
  }

  "one COHERE_API_KEY" should {

    given ProviderRegistry = ProviderRegistry.default

    "serve both the Cohere reranker and a Cohere chat section" in {
      val env = cohereSelected + ("COHERE_API_KEY" -> "co-shared")

      RerankerConfigLoader.load(ReferenceConfig.withEnv("", env)).value.map(_.apiKey) shouldBe Some("co-shared")
      CredentialsRoundTrip.chatSectionKey("cohere", env) shouldBe Right(Some("co-shared"))
    }
  }
