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
    "load OpenAI embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "openai",
        "llm4s.embeddings.openai.baseUrl" -> "https://example.com/v1",
        "llm4s.embeddings.openai.model"   -> "text-embedding-3-small",
        // API key is shared with core OpenAI config keys
        "llm4s.openai.apiKey" -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "openai"
        cfg.baseUrl shouldBe "https://example.com/v1"
        cfg.model shouldBe "text-embedding-3-small"
        cfg.apiKey shouldBe "sk-test"
      }
    }

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

    "load Ollama embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "ollama",
        "llm4s.embeddings.ollama.baseUrl" -> "http://localhost:11434",
        "llm4s.embeddings.ollama.model"   -> "nomic-embed-text"
      )
      withProps(props) {
        val (provider, cfg) = Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "ollama"
        cfg.baseUrl shouldBe "http://localhost:11434"
        cfg.model shouldBe "nomic-embed-text"
        // Ollama doesn't require API key, defaults to "not-required"
        cfg.apiKey shouldBe "not-required"
      }
    }

    "load Ollama embeddings config with defaults" in {
      val props = Map(
        "llm4s.embeddings.provider"     -> "ollama",
        "llm4s.embeddings.ollama.model" -> "mxbai-embed-large"
      )
      withProps(props) {
        val (provider, cfg) = Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "ollama"
        // Default baseUrl when not specified
        cfg.baseUrl shouldBe "http://localhost:11434"
        cfg.model shouldBe "mxbai-embed-large"
        cfg.apiKey shouldBe "not-required"
      }
    }
  }
}
