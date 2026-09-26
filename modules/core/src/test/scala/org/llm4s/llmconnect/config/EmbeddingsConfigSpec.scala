package org.llm4s.llmconnect.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.config.Llm4sConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
    "load VoyageAI embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "voyage",
        "llm4s.embeddings.voyage.baseUrl" -> "https://api.voyage.ai",
        "llm4s.embeddings.voyage.model"   -> "voyage-3-large",
        "llm4s.embeddings.voyage.apiKey"  -> "vk-test"
      )
      withProps(props) {
        val (provider, cfg) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "voyage"
        cfg.baseUrl shouldBe "https://api.voyage.ai"
        cfg.model shouldBe "voyage-3-large"
        cfg.apiKey shouldBe "vk-test"
      }
    }
  }
}
