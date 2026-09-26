package org.llm4s.llmconnect.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.Llm4sConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `Llm4sConfig.embeddings` from `llm4s.*` system properties. This used Voyage until it moved
 * to `llm4s-voyage` (#1132), and now uses core's test `FixtureEmbeddingProvider`.
 */
class EmbeddingsConfigSpec extends AnyWordSpec with Matchers {

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
        val (provider, cfg) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "fixtureembedding"
        cfg.baseUrl shouldBe "https://embeddings.example.test"
        cfg.model shouldBe "fixture-embed-large"
        cfg.apiKey shouldBe "vk-test"
      }
    }
  }
}
