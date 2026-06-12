package org.llm4s.spring

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class Llm4sPropertiesSpec extends AnyFlatSpec with Matchers {

  "Llm4sProperties" should "default to empty strings and standard context window" in {
    val p = new Llm4sProperties
    p.provider shouldBe ""
    p.model shouldBe ""
    p.apiKey shouldBe ""
    p.baseUrl shouldBe ""
    p.organization shouldBe ""
    p.contextWindow shouldBe 128000
    p.reserveCompletion shouldBe 4096
  }

  it should "accept field assignments and reflect them" in {
    val p = new Llm4sProperties
    p.provider = "openai"
    p.model = "gpt-4o"
    p.apiKey = "sk-test"
    p.baseUrl = "https://api.openai.com/v1"
    p.organization = "org-123"
    p.contextWindow = 32000
    p.reserveCompletion = 2048

    p.provider shouldBe "openai"
    p.model shouldBe "gpt-4o"
    p.apiKey shouldBe "sk-test"
    p.baseUrl shouldBe "https://api.openai.com/v1"
    p.organization shouldBe "org-123"
    p.contextWindow shouldBe 32000
    p.reserveCompletion shouldBe 2048
  }

  it should "allow reassignment of each field independently" in {
    val p = new Llm4sProperties
    p.provider = "anthropic"
    p.model = "claude-sonnet-4-5-latest"
    p.provider shouldBe "anthropic"
    p.model shouldBe "claude-sonnet-4-5-latest"
    p.apiKey shouldBe ""
  }
}
