package org.llm4s

import org.llm4s.config.TextEmbeddingModelSettings
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName, ProvidersConfig }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Message, UserMessage }
import org.llm4s.model.TransformationResult
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `TextEmbeddingModelSettings`, `ProvidersConfig` and `TransformationResult` are growth-safe (#2384):
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

  "TextEmbeddingModelSettings" should "hold a ProviderId and an EmbeddingModelConfig, and set each (#2805)" in {
    val model    = EmbeddingModelConfig("text-embedding-3-small", 1536)
    val settings = TextEmbeddingModelSettings(ProviderId("openai"), model)
    settings.provider shouldBe ProviderId("openai")
    settings.model shouldBe model

    settings.withProvider(ProviderId("voyage")) shouldBe TextEmbeddingModelSettings(ProviderId("voyage"), model)
    settings.withModel(model.withDimensions(3072)) shouldBe
      TextEmbeddingModelSettings(ProviderId("openai"), EmbeddingModelConfig("text-embedding-3-small", 3072))
    // No longer a String provider beside a copy of EmbeddingModelConfig's fields.
    assertDoesNotCompile("""TextEmbeddingModelSettings("openai", "m", 1)""")
    assertDoesNotCompile("""org.llm4s.config.Llm4sConfig.TextEmbeddingModelSettings""")
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new TextEmbeddingModelSettings(ProviderId("openai"), EmbeddingModelConfig("m", 1))""")
    assertDoesNotCompile(
      """TextEmbeddingModelSettings(ProviderId("openai"), EmbeddingModelConfig("m", 1)).copy(provider = ProviderId("x"))"""
    )
  }

  "ProvidersConfig" should "build through apply and set each field" in {
    val cfg = ProvidersConfig(Some(main), Map(main -> section))
    cfg.defaultProvider shouldBe Some(main)
    cfg.namedProviders shouldBe Map(main -> section)
    cfg.defaultProviderName shouldBe Right(main)

    cfg.withDefaultProvider(other) shouldBe ProvidersConfig(Some(other), Map(main -> section))
    cfg.withDefaultProvider(None) shouldBe ProvidersConfig(None, Map(main -> section))
    cfg.withDefaultProvider(None).defaultProviderName.isLeft shouldBe true
    cfg.withDefaultProvider(Some(other)) shouldBe ProvidersConfig(Some(other), Map(main -> section))
    cfg.withNamedProviders(Map(other -> section)) shouldBe ProvidersConfig(Some(main), Map(other -> section))
  }

  it should "keep its constructor and copy private" in {
    assertDoesNotCompile("""new ProvidersConfig(None, Map.empty)""")
    assertDoesNotCompile("""ProvidersConfig(None, Map.empty).copy(defaultProvider = None)""")
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
}
