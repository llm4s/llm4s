package org.llm4s.llmconnect.config

import org.llm4s.config.ConfigReader
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmbeddingsConfigSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  "ConfigReader.Embeddings" should {
    "load OpenAI embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "openai",
        "llm4s.embeddings.openai.baseUrl" -> "https://example.com/v1",
        "llm4s.embeddings.openai.model"   -> "text-embedding-3-small",
        // API key is shared with core OpenAI config keys
        "llm4s.openai.apiKey" -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg) = ConfigReader.Embeddings().fold(err => fail(err.toString), identity)
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
        val (provider, cfg) = ConfigReader.Embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "voyage"
        cfg.baseUrl shouldBe "https://api.voyage.ai"
        cfg.model shouldBe "voyage-3-large"
        cfg.apiKey shouldBe "vk-test"
      }
    }
  }
}
