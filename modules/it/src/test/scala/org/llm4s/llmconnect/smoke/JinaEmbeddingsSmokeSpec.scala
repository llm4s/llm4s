package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.provider.{ EmbeddingProvider, JinaEmbeddingProvider }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Jina AI embeddings.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `JINA_API_KEY` environment variable.
 * If the key is absent the tests are skipped gracefully via assume().
 */
class JinaEmbeddingsSmokeSpec extends AnyFlatSpec with Matchers {

  private val jinaApiKey: Option[String] = Option(System.getenv("JINA_API_KEY")).filter(_.nonEmpty)

  private val DefaultJinaBaseUrl = "https://api.jina.ai"
  private val DefaultJinaModel   = "jina-embeddings-v3"
  private val ExpectedDimensions = 1024

  private def makeProvider(apiKey: String): EmbeddingProvider = {
    val cfg = EmbeddingProviderConfig(
      baseUrl = DefaultJinaBaseUrl,
      model = DefaultJinaModel,
      apiKey = apiKey
    )
    JinaEmbeddingProvider.fromConfig(cfg)
  }

  "Jina AI embeddings" should "embed 3 sentences with jina-embeddings-v3" in {
    assume(jinaApiKey.isDefined, "JINA_API_KEY not set — skipping Jina smoke test")

    val provider = makeProvider(jinaApiKey.get)
    val modelCfg = EmbeddingModelConfig(DefaultJinaModel, ExpectedDimensions)
    val request = EmbeddingRequest(
      input = Seq(
        "The quick brown fox jumps over the lazy dog.",
        "Machine learning is transforming enterprise software.",
        "Jina AI provides state-of-the-art embedding models for RAG."
      ),
      model = modelCfg
    )

    val result = provider.embed(request)

    withClue(s"Embedding failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val response = result.toOption.get

    response.embeddings should not be empty
    response.embeddings should have size 3

    response.embeddings.foreach { vec =>
      vec should not be empty
      vec should have size ExpectedDimensions
    }
  }

  it should "embed with retrieval.passage task encoded in model name" in {
    assume(jinaApiKey.isDefined, "JINA_API_KEY not set — skipping Jina smoke test")

    val provider = makeProvider(jinaApiKey.get)
    // Encode task in model name using :: convention supported by JinaEmbeddingProvider
    val modelCfg = EmbeddingModelConfig(s"$DefaultJinaModel::retrieval.passage", ExpectedDimensions)
    val request = EmbeddingRequest(
      input = Seq("Enterprise RAG pipelines benefit from task-specific embeddings."),
      model = modelCfg
    )

    val result = provider.embed(request)

    withClue(s"Embedding with task failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val response = result.toOption.get
    response.embeddings should have size 1
    response.embeddings(0) should have size ExpectedDimensions
  }

  it should "return a 401 error for an invalid API key" in {
    assume(jinaApiKey.isDefined, "JINA_API_KEY not set — skipping Jina smoke test")

    val provider = makeProvider("jina-invalid-key-for-smoke-test")
    val modelCfg = EmbeddingModelConfig(DefaultJinaModel, ExpectedDimensions)
    val request  = EmbeddingRequest(Seq("test"), modelCfg)

    val result = provider.embed(request)

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err.code shouldBe Some("401")
  }
}
