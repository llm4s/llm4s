package org.llm4s.security

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecretConfigToStringSpec extends AnyFlatSpec with Matchers {
  "a config holding a Secret" should "print *** for the credential and register it" in KnownSecrets.isolated {
    val key = "emb-key-0123456789abcdef"
    val cfg = EmbeddingProviderConfig(baseUrl = "https://example.test", model = "m", apiKey = Secret(key))
    (cfg.toString should not).include(key)
    cfg.toString should include("***")
    (org.llm4s.util.Redaction.safeBody(s"echo $key") should not).include(key)
  }
}
