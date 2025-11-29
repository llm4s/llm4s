package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.embeddingsChunking:
 * ensure it mirrors EmbeddingConfig.chunkSize/chunkOverlap/chunkingEnabled
 * under standard llm4s.* configuration.
 */
class Llm4sConfigEmbeddingsChunkingSpec extends AnyWordSpec with Matchers {

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

  "Llm4sConfig.embeddingsChunking" should {
    "fall back to defaults when no chunking config is provided" in {
      val props = Map.empty[String, String]

      withProps(props) {
        val settings = Llm4sConfig.embeddingsChunking().fold(err => fail(err.toString), identity)
        settings.enabled shouldBe true
        settings.size shouldBe 1000
        settings.overlap shouldBe 100
      }
    }

    "respect llm4s.embeddings.chunking overrides" in {
      val props = Map(
        "llm4s.embeddings.chunking.size"    -> "2048",
        "llm4s.embeddings.chunking.overlap" -> "256",
        "llm4s.embeddings.chunking.enabled" -> "false"
      )

      withProps(props) {
        val settings = Llm4sConfig.embeddingsChunking().fold(err => fail(err.toString), identity)
        settings.enabled shouldBe false
        settings.size shouldBe 2048
        settings.overlap shouldBe 256
      }
    }
  }
}
