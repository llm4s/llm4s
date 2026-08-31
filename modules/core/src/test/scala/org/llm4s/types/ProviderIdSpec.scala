package org.llm4s.types

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProviderIdSpec extends AnyFlatSpec with Matchers:

  "ProviderId" should "canonicalise to trimmed lowercase" in {
    ProviderId("OpenAI").asString shouldBe "openai"
    ProviderId("  Anthropic  ").asString shouldBe "anthropic"
    ProviderId("OLLAMA").asString shouldBe "ollama"
    ProviderId("VertexAI").asString shouldBe "vertexai"
  }

  it should "treat differently-spelled forms of the same provider as equal" in {
    ProviderId("OpenAI") shouldBe ProviderId("openai")
    ProviderId(" gemini ") shouldBe ProviderId("GEMINI")
  }

  it should "distinguish different providers" in {
    ProviderId("openai") should not be ProviderId("anthropic")
  }

  it should "round-trip through asString" in {
    val ids = Seq(
      "openai",
      "openrouter",
      "requesty",
      "azure",
      "anthropic",
      "ollama",
      "zai",
      "gemini",
      "deepseek",
      "cohere",
      "mistral",
      "vertexai"
    )
    ids.foreach(id => ProviderId(ProviderId(id).asString) shouldBe ProviderId(id))
  }

  it should "accept ids this build has never heard of" in {
    // The point of the open vocabulary: parsing must not decide what is supported.
    // Whether anything can serve 'bedrock' is a resolution-time question, not a parse-time one.
    ProviderId("bedrock").asString shouldBe "bedrock"
    ProviderId("Some-Vendor").asString shouldBe "some-vendor"
  }

  it should "canonicalise the empty and whitespace-only string to the empty id" in {
    ProviderId("").asString shouldBe ""
    ProviderId("   ").asString shouldBe ""
  }

  it should "be usable as a map key" in {
    val byId = Map(ProviderId("openai") -> 1, ProviderId("ollama") -> 2)
    byId.get(ProviderId("OpenAI")) shouldBe Some(1)
    byId.get(ProviderId("bedrock")) shouldBe None
  }
