package org.llm4s.types

import org.llm4s.types.ProviderModelTypes.ProviderKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProviderKindSpec extends AnyFlatSpec with Matchers:

  "ProviderKind" should "expose all expected provider instances" in {
    ProviderKind.all should have size 10
    (ProviderKind.all should contain).allOf(
      ProviderKind.OpenAI,
      ProviderKind.Azure,
      ProviderKind.Anthropic,
      ProviderKind.OpenRouter,
      ProviderKind.Ollama,
      ProviderKind.Zai,
      ProviderKind.Gemini,
      ProviderKind.Cohere,
      ProviderKind.DeepSeek,
      ProviderKind.Mistral
    )
  }

  it should "expose lowercase provider names" in {
    ProviderKind.OpenAI.name shouldBe "openai"
    ProviderKind.Azure.name shouldBe "azure"
    ProviderKind.Anthropic.name shouldBe "anthropic"
    ProviderKind.OpenRouter.name shouldBe "openrouter"
    ProviderKind.Ollama.name shouldBe "ollama"
    ProviderKind.Zai.name shouldBe "zai"
    ProviderKind.Gemini.name shouldBe "gemini"
    ProviderKind.DeepSeek.name shouldBe "deepseek"
    ProviderKind.Cohere.name shouldBe "cohere"
    ProviderKind.Mistral.name shouldBe "mistral"
  }

  "ProviderKind.fromName" should "parse supported providers case-insensitively" in {
    ProviderKind.fromName("openai") shouldBe Some(ProviderKind.OpenAI)
    ProviderKind.fromName("AZURE") shouldBe Some(ProviderKind.Azure)
    ProviderKind.fromName("Anthropic") shouldBe Some(ProviderKind.Anthropic)
    ProviderKind.fromName("OpenRouter") shouldBe Some(ProviderKind.OpenRouter)
    ProviderKind.fromName("OLLAMA") shouldBe Some(ProviderKind.Ollama)
    ProviderKind.fromName("ZAI") shouldBe Some(ProviderKind.Zai)
    ProviderKind.fromName("GEMINI") shouldBe Some(ProviderKind.Gemini)
    ProviderKind.fromName("Google") shouldBe Some(ProviderKind.Gemini)
    ProviderKind.fromName("DEEPSEEK") shouldBe Some(ProviderKind.DeepSeek)
    ProviderKind.fromName("COHERE") shouldBe Some(ProviderKind.Cohere)
    ProviderKind.fromName("MISTRAL") shouldBe Some(ProviderKind.Mistral)
  }

  it should "return None for unknown providers" in {
    ProviderKind.fromName("unknown") shouldBe None
    ProviderKind.fromName("gpt4") shouldBe None
    ProviderKind.fromName("") shouldBe None
    ProviderKind.fromName("claude") shouldBe None
  }

  "ProviderKind" should "round-trip through name and fromName" in {
    ProviderKind.all.foreach(provider => ProviderKind.fromName(provider.name) shouldBe Some(provider))
  }

  it should "support pattern matching" in {
    def describe(provider: ProviderKind): String = provider match
      case ProviderKind.OpenAI     => "cloud-openai"
      case ProviderKind.Azure      => "cloud-azure"
      case ProviderKind.Anthropic  => "cloud-anthropic"
      case ProviderKind.OpenRouter => "cloud-openrouter"
      case ProviderKind.Ollama     => "local"
      case ProviderKind.Zai        => "cloud-zai"
      case ProviderKind.Gemini     => "cloud-gemini"
      case ProviderKind.DeepSeek   => "cloud-deepseek"
      case ProviderKind.Cohere     => "cloud-cohere"
      case ProviderKind.Mistral    => "cloud-mistral"

    describe(ProviderKind.OpenAI) shouldBe "cloud-openai"
    describe(ProviderKind.Ollama) shouldBe "local"
    describe(ProviderKind.Zai) shouldBe "cloud-zai"
    describe(ProviderKind.Gemini) shouldBe "cloud-gemini"
    describe(ProviderKind.DeepSeek) shouldBe "cloud-deepseek"
    describe(ProviderKind.Cohere) shouldBe "cloud-cohere"
    describe(ProviderKind.Mistral) shouldBe "cloud-mistral"
  }
