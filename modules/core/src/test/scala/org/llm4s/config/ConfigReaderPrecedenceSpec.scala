package org.llm4s.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigReaderPrecedenceSpec extends AnyWordSpec with Matchers {

  "LLMConfig" should {
    "read llm4s.* values from test application.conf" in {
      val cfg = ConfigReader.LLMConfig().fold(err => fail(err.toString), identity)
      cfg.getPath("llm4s.llm.model") shouldBe Some("openai/gpt-4o")
      cfg.get("OPENAI_API_KEY") shouldBe Some("test-key")
      cfg.getPath("llm4s.openai.apiKey") shouldBe Some("test-key")
    }

    "allow -D system property to override application.conf" in {
      val key      = "llm4s.openai.apiKey"
      val original = System.getProperty(key)
      try {
        System.setProperty(key, "overridden-key")
        val cfg = ConfigReader.LLMConfig().fold(err => fail(err.toString), identity)
        cfg.getPath("llm4s.openai.apiKey") shouldBe Some("overridden-key")
        cfg.get("OPENAI_API_KEY") shouldBe Some("overridden-key")
      } finally
        if (original == null) System.clearProperty(key) else System.setProperty(key, original)
    }
  }
}
