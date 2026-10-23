package org.llm4s

import org.llm4s.config.Llm4sConfig.TextEmbeddingModelSettings
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName, ProvidersConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Message, UserMessage }
import org.llm4s.model.TransformationResult
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.util.SecretPatterns.{ SecretMatch, SecretType }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `TextEmbeddingModelSettings`, `ProvidersConfig`, `TransformationResult` and `SecretMatch` are growth-safe (#2384):
 * a private constructor and `copy`, a companion `apply` with the previous parameters and defaults, and a `with*`
 * setter per field. Each setter changes its field and nothing else; `new X(...)` and `.copy(...)` no longer compile.
 */
class GrowthSafeConfigModelUtilTypesSpec extends AnyFlatSpec with Matchers {

  private val section =
    NamedProviderConfig(ProviderId("fixturechat"), ModelName("m"), None, None, Map.empty, Map.empty)
  private val main  = ProviderName("main")
  private val other = ProviderName("other")

  private val options                = CompletionOptions()
  private val messages: Seq[Message] = Seq(UserMessage("hi"))

  "TextEmbeddingModelSettings" should "build through apply and set each field" in {
    val settings = TextEmbeddingModelSettings("openai", "text-embedding-3-small", 1536)
    settings.provider shouldBe "openai"
    settings.modelName shouldBe "text-embedding-3-small"
    settings.dimensions shouldBe 1536

    settings.withProvider("voyage") shouldBe TextEmbeddingModelSettings("voyage", "text-embedding-3-small", 1536)
    settings.withModelName("large") shouldBe TextEmbeddingModelSettings("openai", "large", 1536)
    settings.withDimensions(3072) shouldBe TextEmbeddingModelSettings("openai", "text-embedding-3-small", 3072)

    val TextEmbeddingModelSettings(p, m, d) = settings
    (p, m, d) shouldBe (("openai", "text-embedding-3-small", 1536))
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new TextEmbeddingModelSettings("openai", "m", 1)""")
    assertDoesNotCompile("""TextEmbeddingModelSettings("openai", "m", 1).copy(dimensions = 2)""")
  }

  "ProvidersConfig" should "build through apply and set each field" in {
    val cfg = ProvidersConfig(Some(main), Map(main -> section))
    cfg.selectedProvider shouldBe Some(main)
    cfg.namedProviders shouldBe Map(main -> section)
    cfg.defaultProviderName shouldBe Right(main)

    cfg.withSelectedProvider(other) shouldBe ProvidersConfig(Some(other), Map(main -> section))
    cfg.withSelectedProvider(None) shouldBe ProvidersConfig(None, Map(main -> section))
    cfg.withSelectedProvider(None).defaultProviderName.isLeft shouldBe true
    cfg.withSelectedProvider(Some(other)) shouldBe ProvidersConfig(Some(other), Map(main -> section))
    cfg.withNamedProviders(Map(other -> section)) shouldBe ProvidersConfig(Some(main), Map(other -> section))
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new ProvidersConfig(None, Map.empty)""")
    assertDoesNotCompile("""ProvidersConfig(None, Map.empty).copy(selectedProvider = None)""")
  }

  "TransformationResult" should "default requiresFakeStreaming to false" in {
    val result = TransformationResult(options, messages)
    result.options shouldBe options
    result.messages shouldBe messages
    result.requiresFakeStreaming shouldBe false
    TransformationResult(options, messages, requiresFakeStreaming = true).requiresFakeStreaming shouldBe true
  }

  it should "set each field" in {
    val result     = TransformationResult(options, messages)
    val newOptions = CompletionOptions().withTemperature(0.1)
    val newMsgs    = Seq(UserMessage("other"))
    result.withOptions(newOptions) shouldBe TransformationResult(newOptions, messages)
    result.withMessages(newMsgs) shouldBe TransformationResult(options, newMsgs)
    result
      .withRequiresFakeStreaming(true) shouldBe TransformationResult(options, messages, requiresFakeStreaming = true)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new TransformationResult(options, messages, false)""")
    assertDoesNotCompile("""TransformationResult(options, messages).copy(requiresFakeStreaming = true)""")
  }

  "SecretMatch" should "build through apply and set each field" in {
    val m = SecretMatch(SecretType.OpenAIKey, "sk-abc", 3, 9)
    m.secretType shouldBe SecretType.OpenAIKey
    m.value shouldBe "sk-abc"
    m.startIndex shouldBe 3
    m.endIndex shouldBe 9
    m.redactedValue shouldBe SecretType.OpenAIKey.placeholder

    m.withSecretType(SecretType.AnthropicKey) shouldBe SecretMatch(SecretType.AnthropicKey, "sk-abc", 3, 9)
    m.withValue("sk-xyz") shouldBe SecretMatch(SecretType.OpenAIKey, "sk-xyz", 3, 9)
    m.withStartIndex(0) shouldBe SecretMatch(SecretType.OpenAIKey, "sk-abc", 0, 9)
    m.withEndIndex(10) shouldBe SecretMatch(SecretType.OpenAIKey, "sk-abc", 3, 10)
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new SecretMatch(SecretType.OpenAIKey, "v", 0, 1)""")
    assertDoesNotCompile("""SecretMatch(SecretType.OpenAIKey, "v", 0, 1).copy(value = "w")""")
  }
}
