package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for LLMProvider enumeration and utilities.
 */
class LLMProviderSpec extends AnyFlatSpec with Matchers {

  // ============ Provider Instances ============

  "LLMProvider" should "have all expected provider instances" in {
    LLMProvider.all should have size 9
    (LLMProvider.all should contain).allOf(
      LLMProvider.OpenAI,
      LLMProvider.Azure,
      LLMProvider.Anthropic,
      LLMProvider.OpenRouter,
      LLMProvider.Ollama,
      LLMProvider.Zai,
      LLMProvider.Gemini,
      LLMProvider.Cohere,
      LLMProvider.DeepSeek
    )
  }

  it should "have exactly 9 providers" in {
    LLMProvider.all should have size 9
  }

  // ============ Provider Names ============

  "LLMProvider.name" should "return correct name for OpenAI" in {
    LLMProvider.OpenAI.name shouldBe "openai"
  }

  it should "return correct name for Azure" in {
    LLMProvider.Azure.name shouldBe "azure"
  }

  it should "return correct name for Anthropic" in {
    LLMProvider.Anthropic.name shouldBe "anthropic"
  }

  it should "return correct name for OpenRouter" in {
    LLMProvider.OpenRouter.name shouldBe "openrouter"
  }

  it should "return correct name for Ollama" in {
    LLMProvider.Ollama.name shouldBe "ollama"
  }

  it should "return correct name for Zai" in {
    LLMProvider.Zai.name shouldBe "zai"
  }

  it should "return correct name for Gemini" in {
    LLMProvider.Gemini.name shouldBe "gemini"
  }

  it should "return correct name for DeepSeek" in {
    LLMProvider.DeepSeek.name shouldBe "deepseek"
  }

  it should "return correct name for Cohere" in {
    LLMProvider.Cohere.name shouldBe "cohere"
  }

  // ============ fromName Parsing ============

  "LLMProvider.fromName" should "parse 'openai' correctly" in {
    LLMProvider.fromName("openai") shouldBe Some(LLMProvider.OpenAI)
  }

  it should "parse 'azure' correctly" in {
    LLMProvider.fromName("azure") shouldBe Some(LLMProvider.Azure)
  }

  it should "parse 'anthropic' correctly" in {
    LLMProvider.fromName("anthropic") shouldBe Some(LLMProvider.Anthropic)
  }

  it should "parse 'openrouter' correctly" in {
    LLMProvider.fromName("openrouter") shouldBe Some(LLMProvider.OpenRouter)
  }

  it should "parse 'ollama' correctly" in {
    LLMProvider.fromName("ollama") shouldBe Some(LLMProvider.Ollama)
  }

  it should "parse 'zai' correctly" in {
    LLMProvider.fromName("zai") shouldBe Some(LLMProvider.Zai)
  }

  it should "parse 'gemini' correctly" in {
    LLMProvider.fromName("gemini") shouldBe Some(LLMProvider.Gemini)
  }

  it should "parse 'google' as Gemini" in {
    LLMProvider.fromName("google") shouldBe Some(LLMProvider.Gemini)
  }

  it should "parse 'deepseek' correctly" in {
    LLMProvider.fromName("deepseek") shouldBe Some(LLMProvider.DeepSeek)
  }

  it should "parse 'cohere' correctly" in {
    LLMProvider.fromName("cohere") shouldBe Some(LLMProvider.Cohere)
  }

  it should "be case insensitive" in {
    LLMProvider.fromName("OpenAI") shouldBe Some(LLMProvider.OpenAI)
    LLMProvider.fromName("AZURE") shouldBe Some(LLMProvider.Azure)
    LLMProvider.fromName("Anthropic") shouldBe Some(LLMProvider.Anthropic)
    LLMProvider.fromName("OpenRouter") shouldBe Some(LLMProvider.OpenRouter)
    LLMProvider.fromName("OLLAMA") shouldBe Some(LLMProvider.Ollama)
    LLMProvider.fromName("ZAI") shouldBe Some(LLMProvider.Zai)
    LLMProvider.fromName("GEMINI") shouldBe Some(LLMProvider.Gemini)
    LLMProvider.fromName("Google") shouldBe Some(LLMProvider.Gemini)
    LLMProvider.fromName("DEEPSEEK") shouldBe Some(LLMProvider.DeepSeek)
    LLMProvider.fromName("COHERE") shouldBe Some(LLMProvider.Cohere)
  }

  it should "return None for unknown providers" in {
    LLMProvider.fromName("unknown") shouldBe None
    LLMProvider.fromName("gpt4") shouldBe None
    LLMProvider.fromName("") shouldBe None
    LLMProvider.fromName("claude") shouldBe None
  }

  // ============ Round-trip ============

  "LLMProvider" should "round-trip through name and fromName" in {
    LLMProvider.all.foreach { provider =>
      val name   = provider.name
      val parsed = LLMProvider.fromName(name)
      parsed shouldBe Some(provider)
    }
  }

  // ============ Type Safety ============

  "LLMProvider instances" should "be distinguishable" in {
    LLMProvider.OpenAI should not be LLMProvider.Azure
    LLMProvider.Anthropic should not be LLMProvider.OpenRouter
    LLMProvider.Ollama should not be LLMProvider.OpenAI
  }

  it should "support pattern matching" in {
    def describe(provider: LLMProvider): String = provider match {
      case LLMProvider.OpenAI     => "cloud-openai"
      case LLMProvider.Azure      => "cloud-azure"
      case LLMProvider.Anthropic  => "cloud-anthropic"
      case LLMProvider.OpenRouter => "cloud-openrouter"
      case LLMProvider.Ollama     => "local"
      case LLMProvider.Zai        => "cloud-zai"
      case LLMProvider.Gemini     => "cloud-gemini"
      case LLMProvider.DeepSeek   => "cloud-deepseek"
      case LLMProvider.Cohere     => "cloud-cohere"
    }

    describe(LLMProvider.OpenAI) shouldBe "cloud-openai"
    describe(LLMProvider.Ollama) shouldBe "local"
    describe(LLMProvider.Zai) shouldBe "cloud-zai"
    describe(LLMProvider.Gemini) shouldBe "cloud-gemini"
    describe(LLMProvider.DeepSeek) shouldBe "cloud-deepseek"
    describe(LLMProvider.Cohere) shouldBe "cloud-cohere"
  }
}
