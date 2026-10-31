package org.llm4s.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Each metadata and capability setter changes its own field and leaves the original unchanged. */
class ModelMetadataSettersSpec extends AnyFlatSpec with Matchers {

  private def metadata(
    modelId: String = "fixture-model",
    provider: String = "fixture-provider",
    mode: ModelMode = ModelMode.Chat,
    maxInputTokens: Option[Int] = None,
    maxOutputTokens: Option[Int] = None,
    inputCostPerToken: Option[Double] = None,
    outputCostPerToken: Option[Double] = None,
    capabilities: ModelCapabilities = ModelCapabilities(),
    pricing: ModelPricing = ModelPricing(),
    deprecationDate: Option[String] = None
  ): ModelMetadata = ModelMetadata(
    modelId = modelId,
    provider = provider,
    mode = mode,
    maxInputTokens = maxInputTokens,
    maxOutputTokens = maxOutputTokens,
    inputCostPerToken = inputCostPerToken,
    outputCostPerToken = outputCostPerToken,
    capabilities = capabilities,
    pricing = pricing,
    deprecationDate = deprecationDate
  )

  private val baseMetadata     = metadata()
  private val baseCapabilities = ModelCapabilities()

  "ModelMetadata setters" should "set modelId without changing other fields" in {
    baseMetadata.withModelId("replacement-model") shouldBe metadata(modelId = "replacement-model")
    baseMetadata shouldBe metadata()
  }

  it should "set provider without changing other fields" in {
    baseMetadata.withProvider("replacement-provider") shouldBe metadata(provider = "replacement-provider")
    baseMetadata shouldBe metadata()
  }

  it should "set mode without changing other fields" in {
    baseMetadata.withMode(ModelMode.Embedding) shouldBe metadata(mode = ModelMode.Embedding)
    baseMetadata shouldBe metadata()
  }

  it should "set capabilities without changing other fields" in {
    baseMetadata.withCapabilities(ModelCapabilities(supportsVision = Some(true))) shouldBe metadata(capabilities =
      ModelCapabilities(supportsVision = Some(true))
    )
    baseMetadata shouldBe metadata()
  }

  it should "set pricing without changing other fields" in {
    baseMetadata.withPricing(ModelPricing(inputCostPerToken = Some(0.002))) shouldBe metadata(pricing =
      ModelPricing(inputCostPerToken = Some(0.002))
    )
    baseMetadata shouldBe metadata()
  }

  it should "set, replace and clear maxInputTokens" in {
    val populated = metadata(maxInputTokens = Some(8192))

    baseMetadata.withMaxInputTokens(8192) shouldBe populated
    baseMetadata.withMaxInputTokens(Some(8192)) shouldBe populated
    populated.withMaxInputTokens(4096) shouldBe metadata(maxInputTokens = Some(4096))
    populated.withMaxInputTokens(Some(4096)) shouldBe metadata(maxInputTokens = Some(4096))
    populated.withMaxInputTokens(None) shouldBe baseMetadata
    baseMetadata.withMaxInputTokens(8192).withMaxInputTokens(None) shouldBe baseMetadata
    populated shouldBe metadata(maxInputTokens = Some(8192))
    baseMetadata shouldBe metadata()
  }

  it should "set, replace and clear maxOutputTokens" in {
    val populated = metadata(maxOutputTokens = Some(1024))

    baseMetadata.withMaxOutputTokens(1024) shouldBe populated
    baseMetadata.withMaxOutputTokens(Some(1024)) shouldBe populated
    populated.withMaxOutputTokens(512) shouldBe metadata(maxOutputTokens = Some(512))
    populated.withMaxOutputTokens(Some(512)) shouldBe metadata(maxOutputTokens = Some(512))
    populated.withMaxOutputTokens(None) shouldBe baseMetadata
    baseMetadata.withMaxOutputTokens(1024).withMaxOutputTokens(None) shouldBe baseMetadata
    populated shouldBe metadata(maxOutputTokens = Some(1024))
    baseMetadata shouldBe metadata()
  }

  it should "set, replace and clear inputCostPerToken" in {
    val populated = metadata(inputCostPerToken = Some(0.001))

    baseMetadata.withInputCostPerToken(0.001) shouldBe populated
    baseMetadata.withInputCostPerToken(Some(0.001)) shouldBe populated
    populated.withInputCostPerToken(0.002) shouldBe metadata(inputCostPerToken = Some(0.002))
    populated.withInputCostPerToken(Some(0.002)) shouldBe metadata(inputCostPerToken = Some(0.002))
    populated.withInputCostPerToken(None) shouldBe baseMetadata
    baseMetadata.withInputCostPerToken(0.001).withInputCostPerToken(None) shouldBe baseMetadata
    populated shouldBe metadata(inputCostPerToken = Some(0.001))
    baseMetadata shouldBe metadata()
  }

  it should "set, replace and clear outputCostPerToken" in {
    val populated = metadata(outputCostPerToken = Some(0.003))

    baseMetadata.withOutputCostPerToken(0.003) shouldBe populated
    baseMetadata.withOutputCostPerToken(Some(0.003)) shouldBe populated
    populated.withOutputCostPerToken(0.004) shouldBe metadata(outputCostPerToken = Some(0.004))
    populated.withOutputCostPerToken(Some(0.004)) shouldBe metadata(outputCostPerToken = Some(0.004))
    populated.withOutputCostPerToken(None) shouldBe baseMetadata
    baseMetadata.withOutputCostPerToken(0.003).withOutputCostPerToken(None) shouldBe baseMetadata
    populated shouldBe metadata(outputCostPerToken = Some(0.003))
    baseMetadata shouldBe metadata()
  }

  it should "set, replace and clear deprecationDate" in {
    val populated = metadata(deprecationDate = Some("2099-12-31"))

    baseMetadata.withDeprecationDate("2099-12-31") shouldBe populated
    baseMetadata.withDeprecationDate(Some("2099-12-31")) shouldBe populated
    populated.withDeprecationDate("2098-01-01") shouldBe metadata(deprecationDate = Some("2098-01-01"))
    populated.withDeprecationDate(Some("2098-01-01")) shouldBe metadata(deprecationDate = Some("2098-01-01"))
    populated.withDeprecationDate(None) shouldBe baseMetadata
    baseMetadata.withDeprecationDate("2099-12-31").withDeprecationDate(None) shouldBe baseMetadata
    populated shouldBe metadata(deprecationDate = Some("2099-12-31"))
    baseMetadata shouldBe metadata()
  }

  it should "chain distinct fields in either order and preserve populated fields" in {
    val original = metadata(
      maxInputTokens = Some(4096),
      inputCostPerToken = Some(0.001),
      capabilities = ModelCapabilities(supportsVision = Some(false)),
      pricing = ModelPricing(outputCostPerToken = Some(0.003))
    )
    val expected = metadata(
      provider = "replacement-provider",
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(1024),
      inputCostPerToken = Some(0.001),
      capabilities = ModelCapabilities(supportsVision = Some(false)),
      pricing = ModelPricing(outputCostPerToken = Some(0.003))
    )

    original.withProvider("replacement-provider").withMaxOutputTokens(1024) shouldBe expected
    original.withMaxOutputTokens(Some(1024)).withProvider("replacement-provider") shouldBe expected
    original shouldBe metadata(
      maxInputTokens = Some(4096),
      inputCostPerToken = Some(0.001),
      capabilities = ModelCapabilities(supportsVision = Some(false)),
      pricing = ModelPricing(outputCostPerToken = Some(0.003))
    )
  }

  "ModelCapabilities setters" should "set, replace and clear supportsFunctionCalling" in {
    val enabled  = ModelCapabilities(supportsFunctionCalling = Some(true))
    val disabled = ModelCapabilities(supportsFunctionCalling = Some(false))

    baseCapabilities.withSupportsFunctionCalling(true) shouldBe enabled
    baseCapabilities.withSupportsFunctionCalling(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsFunctionCalling(false) shouldBe disabled
    baseCapabilities.withSupportsFunctionCalling(Some(false)) shouldBe disabled
    enabled.withSupportsFunctionCalling(false) shouldBe disabled
    disabled.withSupportsFunctionCalling(Some(true)) shouldBe enabled
    enabled.withSupportsFunctionCalling(None) shouldBe baseCapabilities
    disabled.withSupportsFunctionCalling(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsFunctionCalling = Some(true))
    disabled shouldBe ModelCapabilities(supportsFunctionCalling = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsParallelFunctionCalling" in {
    val enabled  = ModelCapabilities(supportsParallelFunctionCalling = Some(true))
    val disabled = ModelCapabilities(supportsParallelFunctionCalling = Some(false))

    baseCapabilities.withSupportsParallelFunctionCalling(true) shouldBe enabled
    baseCapabilities.withSupportsParallelFunctionCalling(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsParallelFunctionCalling(false) shouldBe disabled
    baseCapabilities.withSupportsParallelFunctionCalling(Some(false)) shouldBe disabled
    enabled.withSupportsParallelFunctionCalling(false) shouldBe disabled
    disabled.withSupportsParallelFunctionCalling(Some(true)) shouldBe enabled
    enabled.withSupportsParallelFunctionCalling(None) shouldBe baseCapabilities
    disabled.withSupportsParallelFunctionCalling(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsParallelFunctionCalling = Some(true))
    disabled shouldBe ModelCapabilities(supportsParallelFunctionCalling = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsVision" in {
    val enabled  = ModelCapabilities(supportsVision = Some(true))
    val disabled = ModelCapabilities(supportsVision = Some(false))

    baseCapabilities.withSupportsVision(true) shouldBe enabled
    baseCapabilities.withSupportsVision(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsVision(false) shouldBe disabled
    baseCapabilities.withSupportsVision(Some(false)) shouldBe disabled
    enabled.withSupportsVision(false) shouldBe disabled
    disabled.withSupportsVision(Some(true)) shouldBe enabled
    enabled.withSupportsVision(None) shouldBe baseCapabilities
    disabled.withSupportsVision(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsVision = Some(true))
    disabled shouldBe ModelCapabilities(supportsVision = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsPromptCaching" in {
    val enabled  = ModelCapabilities(supportsPromptCaching = Some(true))
    val disabled = ModelCapabilities(supportsPromptCaching = Some(false))

    baseCapabilities.withSupportsPromptCaching(true) shouldBe enabled
    baseCapabilities.withSupportsPromptCaching(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsPromptCaching(false) shouldBe disabled
    baseCapabilities.withSupportsPromptCaching(Some(false)) shouldBe disabled
    enabled.withSupportsPromptCaching(false) shouldBe disabled
    disabled.withSupportsPromptCaching(Some(true)) shouldBe enabled
    enabled.withSupportsPromptCaching(None) shouldBe baseCapabilities
    disabled.withSupportsPromptCaching(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsPromptCaching = Some(true))
    disabled shouldBe ModelCapabilities(supportsPromptCaching = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsReasoning" in {
    val enabled  = ModelCapabilities(supportsReasoning = Some(true))
    val disabled = ModelCapabilities(supportsReasoning = Some(false))

    baseCapabilities.withSupportsReasoning(true) shouldBe enabled
    baseCapabilities.withSupportsReasoning(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsReasoning(false) shouldBe disabled
    baseCapabilities.withSupportsReasoning(Some(false)) shouldBe disabled
    enabled.withSupportsReasoning(false) shouldBe disabled
    disabled.withSupportsReasoning(Some(true)) shouldBe enabled
    enabled.withSupportsReasoning(None) shouldBe baseCapabilities
    disabled.withSupportsReasoning(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsReasoning = Some(true))
    disabled shouldBe ModelCapabilities(supportsReasoning = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsResponseSchema" in {
    val enabled  = ModelCapabilities(supportsResponseSchema = Some(true))
    val disabled = ModelCapabilities(supportsResponseSchema = Some(false))

    baseCapabilities.withSupportsResponseSchema(true) shouldBe enabled
    baseCapabilities.withSupportsResponseSchema(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsResponseSchema(false) shouldBe disabled
    baseCapabilities.withSupportsResponseSchema(Some(false)) shouldBe disabled
    enabled.withSupportsResponseSchema(false) shouldBe disabled
    disabled.withSupportsResponseSchema(Some(true)) shouldBe enabled
    enabled.withSupportsResponseSchema(None) shouldBe baseCapabilities
    disabled.withSupportsResponseSchema(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsResponseSchema = Some(true))
    disabled shouldBe ModelCapabilities(supportsResponseSchema = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsSystemMessages" in {
    val enabled  = ModelCapabilities(supportsSystemMessages = Some(true))
    val disabled = ModelCapabilities(supportsSystemMessages = Some(false))

    baseCapabilities.withSupportsSystemMessages(true) shouldBe enabled
    baseCapabilities.withSupportsSystemMessages(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsSystemMessages(false) shouldBe disabled
    baseCapabilities.withSupportsSystemMessages(Some(false)) shouldBe disabled
    enabled.withSupportsSystemMessages(false) shouldBe disabled
    disabled.withSupportsSystemMessages(Some(true)) shouldBe enabled
    enabled.withSupportsSystemMessages(None) shouldBe baseCapabilities
    disabled.withSupportsSystemMessages(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsSystemMessages = Some(true))
    disabled shouldBe ModelCapabilities(supportsSystemMessages = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsPdfInput" in {
    val enabled  = ModelCapabilities(supportsPdfInput = Some(true))
    val disabled = ModelCapabilities(supportsPdfInput = Some(false))

    baseCapabilities.withSupportsPdfInput(true) shouldBe enabled
    baseCapabilities.withSupportsPdfInput(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsPdfInput(false) shouldBe disabled
    baseCapabilities.withSupportsPdfInput(Some(false)) shouldBe disabled
    enabled.withSupportsPdfInput(false) shouldBe disabled
    disabled.withSupportsPdfInput(Some(true)) shouldBe enabled
    enabled.withSupportsPdfInput(None) shouldBe baseCapabilities
    disabled.withSupportsPdfInput(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsPdfInput = Some(true))
    disabled shouldBe ModelCapabilities(supportsPdfInput = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsAudioInput" in {
    val enabled  = ModelCapabilities(supportsAudioInput = Some(true))
    val disabled = ModelCapabilities(supportsAudioInput = Some(false))

    baseCapabilities.withSupportsAudioInput(true) shouldBe enabled
    baseCapabilities.withSupportsAudioInput(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsAudioInput(false) shouldBe disabled
    baseCapabilities.withSupportsAudioInput(Some(false)) shouldBe disabled
    enabled.withSupportsAudioInput(false) shouldBe disabled
    disabled.withSupportsAudioInput(Some(true)) shouldBe enabled
    enabled.withSupportsAudioInput(None) shouldBe baseCapabilities
    disabled.withSupportsAudioInput(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsAudioInput = Some(true))
    disabled shouldBe ModelCapabilities(supportsAudioInput = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsAudioOutput" in {
    val enabled  = ModelCapabilities(supportsAudioOutput = Some(true))
    val disabled = ModelCapabilities(supportsAudioOutput = Some(false))

    baseCapabilities.withSupportsAudioOutput(true) shouldBe enabled
    baseCapabilities.withSupportsAudioOutput(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsAudioOutput(false) shouldBe disabled
    baseCapabilities.withSupportsAudioOutput(Some(false)) shouldBe disabled
    enabled.withSupportsAudioOutput(false) shouldBe disabled
    disabled.withSupportsAudioOutput(Some(true)) shouldBe enabled
    enabled.withSupportsAudioOutput(None) shouldBe baseCapabilities
    disabled.withSupportsAudioOutput(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsAudioOutput = Some(true))
    disabled shouldBe ModelCapabilities(supportsAudioOutput = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsWebSearch" in {
    val enabled  = ModelCapabilities(supportsWebSearch = Some(true))
    val disabled = ModelCapabilities(supportsWebSearch = Some(false))

    baseCapabilities.withSupportsWebSearch(true) shouldBe enabled
    baseCapabilities.withSupportsWebSearch(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsWebSearch(false) shouldBe disabled
    baseCapabilities.withSupportsWebSearch(Some(false)) shouldBe disabled
    enabled.withSupportsWebSearch(false) shouldBe disabled
    disabled.withSupportsWebSearch(Some(true)) shouldBe enabled
    enabled.withSupportsWebSearch(None) shouldBe baseCapabilities
    disabled.withSupportsWebSearch(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsWebSearch = Some(true))
    disabled shouldBe ModelCapabilities(supportsWebSearch = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsComputerUse" in {
    val enabled  = ModelCapabilities(supportsComputerUse = Some(true))
    val disabled = ModelCapabilities(supportsComputerUse = Some(false))

    baseCapabilities.withSupportsComputerUse(true) shouldBe enabled
    baseCapabilities.withSupportsComputerUse(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsComputerUse(false) shouldBe disabled
    baseCapabilities.withSupportsComputerUse(Some(false)) shouldBe disabled
    enabled.withSupportsComputerUse(false) shouldBe disabled
    disabled.withSupportsComputerUse(Some(true)) shouldBe enabled
    enabled.withSupportsComputerUse(None) shouldBe baseCapabilities
    disabled.withSupportsComputerUse(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsComputerUse = Some(true))
    disabled shouldBe ModelCapabilities(supportsComputerUse = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsAssistantPrefill" in {
    val enabled  = ModelCapabilities(supportsAssistantPrefill = Some(true))
    val disabled = ModelCapabilities(supportsAssistantPrefill = Some(false))

    baseCapabilities.withSupportsAssistantPrefill(true) shouldBe enabled
    baseCapabilities.withSupportsAssistantPrefill(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsAssistantPrefill(false) shouldBe disabled
    baseCapabilities.withSupportsAssistantPrefill(Some(false)) shouldBe disabled
    enabled.withSupportsAssistantPrefill(false) shouldBe disabled
    disabled.withSupportsAssistantPrefill(Some(true)) shouldBe enabled
    enabled.withSupportsAssistantPrefill(None) shouldBe baseCapabilities
    disabled.withSupportsAssistantPrefill(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsAssistantPrefill = Some(true))
    disabled shouldBe ModelCapabilities(supportsAssistantPrefill = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsToolChoice" in {
    val enabled  = ModelCapabilities(supportsToolChoice = Some(true))
    val disabled = ModelCapabilities(supportsToolChoice = Some(false))

    baseCapabilities.withSupportsToolChoice(true) shouldBe enabled
    baseCapabilities.withSupportsToolChoice(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsToolChoice(false) shouldBe disabled
    baseCapabilities.withSupportsToolChoice(Some(false)) shouldBe disabled
    enabled.withSupportsToolChoice(false) shouldBe disabled
    disabled.withSupportsToolChoice(Some(true)) shouldBe enabled
    enabled.withSupportsToolChoice(None) shouldBe baseCapabilities
    disabled.withSupportsToolChoice(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsToolChoice = Some(true))
    disabled shouldBe ModelCapabilities(supportsToolChoice = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportsNativeStreaming" in {
    val enabled  = ModelCapabilities(supportsNativeStreaming = Some(true))
    val disabled = ModelCapabilities(supportsNativeStreaming = Some(false))

    baseCapabilities.withSupportsNativeStreaming(true) shouldBe enabled
    baseCapabilities.withSupportsNativeStreaming(Some(true)) shouldBe enabled
    baseCapabilities.withSupportsNativeStreaming(false) shouldBe disabled
    baseCapabilities.withSupportsNativeStreaming(Some(false)) shouldBe disabled
    enabled.withSupportsNativeStreaming(false) shouldBe disabled
    disabled.withSupportsNativeStreaming(Some(true)) shouldBe enabled
    enabled.withSupportsNativeStreaming(None) shouldBe baseCapabilities
    disabled.withSupportsNativeStreaming(None) shouldBe baseCapabilities
    enabled shouldBe ModelCapabilities(supportsNativeStreaming = Some(true))
    disabled shouldBe ModelCapabilities(supportsNativeStreaming = Some(false))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear supportedRegions" in {
    val populated   = ModelCapabilities(supportedRegions = Some(List("region-a", "region-b")))
    val replacement = ModelCapabilities(supportedRegions = Some(List.empty[String]))

    baseCapabilities.withSupportedRegions(List("region-a", "region-b")) shouldBe populated
    baseCapabilities.withSupportedRegions(Some(List("region-a", "region-b"))) shouldBe populated
    populated.withSupportedRegions(List.empty[String]) shouldBe replacement
    populated.withSupportedRegions(Some(List.empty[String])) shouldBe replacement
    populated.withSupportedRegions(None) shouldBe baseCapabilities
    baseCapabilities
      .withSupportedRegions(List("region-a", "region-b"))
      .withSupportedRegions(None) shouldBe baseCapabilities
    populated shouldBe ModelCapabilities(supportedRegions = Some(List("region-a", "region-b")))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear disallowedParams" in {
    val populated   = ModelCapabilities(disallowedParams = Some(Set("fixture-param-a", "fixture-param-b")))
    val replacement = ModelCapabilities(disallowedParams = Some(Set.empty[String]))

    baseCapabilities.withDisallowedParams(Set("fixture-param-a", "fixture-param-b")) shouldBe populated
    baseCapabilities.withDisallowedParams(Some(Set("fixture-param-a", "fixture-param-b"))) shouldBe populated
    populated.withDisallowedParams(Set.empty[String]) shouldBe replacement
    populated.withDisallowedParams(Some(Set.empty[String])) shouldBe replacement
    populated.withDisallowedParams(None) shouldBe baseCapabilities
    baseCapabilities
      .withDisallowedParams(Set("fixture-param-a", "fixture-param-b"))
      .withDisallowedParams(None) shouldBe baseCapabilities
    populated shouldBe ModelCapabilities(disallowedParams = Some(Set("fixture-param-a", "fixture-param-b")))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "set, replace and clear temperatureConstraint" in {
    val populated   = ModelCapabilities(temperatureConstraint = Some((0.2, 0.8)))
    val replacement = ModelCapabilities(temperatureConstraint = Some((0.0, 1.0)))

    baseCapabilities.withTemperatureConstraint((0.2, 0.8)) shouldBe populated
    baseCapabilities.withTemperatureConstraint(Some((0.2, 0.8))) shouldBe populated
    populated.withTemperatureConstraint((0.0, 1.0)) shouldBe replacement
    populated.withTemperatureConstraint(Some((0.0, 1.0))) shouldBe replacement
    populated.withTemperatureConstraint(None) shouldBe baseCapabilities
    baseCapabilities.withTemperatureConstraint((0.2, 0.8)).withTemperatureConstraint(None) shouldBe baseCapabilities
    populated shouldBe ModelCapabilities(temperatureConstraint = Some((0.2, 0.8)))
    baseCapabilities shouldBe ModelCapabilities()
  }

  it should "chain distinct fields in either order and preserve populated fields" in {
    val original = ModelCapabilities(
      supportsFunctionCalling = Some(false),
      supportsVision = Some(false),
      supportedRegions = Some(List("region-a", "region-b")),
      disallowedParams = Some(Set("fixture-param")),
      temperatureConstraint = Some((0.2, 0.8))
    )
    val expected = ModelCapabilities(
      supportsFunctionCalling = Some(false),
      supportsVision = Some(true),
      supportsPdfInput = Some(false),
      supportedRegions = Some(List("region-a", "region-b")),
      disallowedParams = Some(Set("fixture-param")),
      temperatureConstraint = Some((0.2, 0.8))
    )

    original.withSupportsVision(true).withSupportsPdfInput(Some(false)) shouldBe expected
    original.withSupportsPdfInput(false).withSupportsVision(Some(true)) shouldBe expected
    original shouldBe ModelCapabilities(
      supportsFunctionCalling = Some(false),
      supportsVision = Some(false),
      supportedRegions = Some(List("region-a", "region-b")),
      disallowedParams = Some(Set("fixture-param")),
      temperatureConstraint = Some((0.2, 0.8))
    )
  }
}
