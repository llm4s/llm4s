package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.embeddings under controlled configuration.
 *
 * The OpenAI cases moved to `llm4s-openai`'s `OpenAIEmbeddingsSpec` with the provider (#1132).
 * The Voyage cases use core's test `FixtureEmbeddingProvider` since Voyage moved to
 * `llm4s-voyage`, whose `VoyageEmbeddingsSpec` has their Voyage versions.
 */
class Llm4sConfigEmbeddingsSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  "Llm4sConfig.embeddings" should {
    "load embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"                 -> "fixtureembedding",
        "llm4s.embeddings.fixtureembedding.baseUrl" -> "https://embeddings.example.test",
        "llm4s.embeddings.fixtureembedding.model"   -> "fixture-embed-large",
        "llm4s.embeddings.fixtureembedding.apiKey"  -> "vk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "fixtureembedding"
        cfg.baseUrl shouldBe "https://embeddings.example.test"
        cfg.model shouldBe "fixture-embed-large"
        cfg.apiKey shouldBe "vk-test"
      }
    }

    // --- Unified EMBEDDING_MODEL format tests ---

    "load embeddings via unified EMBEDDING_MODEL format" in {
      val props = Map(
        "llm4s.embeddings.model"                   -> "fixtureembedding/fixture-embed-small",
        "llm4s.embeddings.fixtureembedding.apiKey" -> "vk-test"
        // No explicit baseUrl - should use default
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "fixtureembedding"
        cfg.model shouldBe "fixture-embed-small"
        cfg.baseUrl shouldBe "https://fixtureembedding.invalid/v1" // Default base URL
        cfg.apiKey shouldBe "vk-test"
      }
    }

    "prefer unified model format over legacy provider" in {
      val props = Map(
        "llm4s.embeddings.model"                   -> "fixtureembedding/fixture-embed-large", // Takes precedence
        "llm4s.embeddings.provider"                -> "openai",                               // Should be ignored
        "llm4s.embeddings.fixtureembedding.apiKey" -> "vk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "fixtureembedding" // Unified format wins
        cfg.model shouldBe "fixture-embed-large"
      }
    }

    "reject invalid embedding model format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "invalid-format-no-slash"
      )
      withProps(props) {
        val result = Llm4sConfig.embeddings()
        result.isLeft shouldBe true
        result.left.getOrElse(fail()).message should include("Invalid embedding model format")
      }
    }

    "reject unknown embedding provider in unified format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "unknown-provider/some-model"
      )
      withProps(props) {
        val result = Llm4sConfig.embeddings()
        result.isLeft shouldBe true
        result.left.getOrElse(fail()).message should include("is not registered")
      }
    }
  }

  "core's reference.conf" should {
    "bind none of the embeddings sample's settings, which moved to modules/samples (#2265)" in {
      val embeddings = ConfigFactory
        .parseResources(getClass.getClassLoader, "reference.conf")
        .resolve()
        .getConfig("llm4s.embeddings")
      Seq("inputPath", "inputPaths", "query", "chunking", "ui", "experimentalStubs", "localModels").foreach { key =>
        withClue(key)(embeddings.hasPath(key) shouldBe false)
      }
    }
  }
}
