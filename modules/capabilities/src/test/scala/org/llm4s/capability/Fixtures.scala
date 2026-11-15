package org.llm4s.capability

import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService }

/** Model metadata for the specs: one model with every capability and one with none. */
object Fixtures {

  def model(id: String, capabilities: ModelCapabilities): ModelMetadata =
    ModelMetadata(
      modelId = id,
      provider = "acme",
      mode = ModelMode.Chat,
      maxInputTokens = Some(8192),
      maxOutputTokens = Some(1024),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = capabilities,
      pricing = ModelPricing(),
      deprecationDate = None
    )

  val everything: ModelMetadata = model(
    "omni",
    ModelCapabilities(
      supportsFunctionCalling = Some(true),
      supportsParallelFunctionCalling = Some(true),
      supportsVision = Some(true),
      supportsPromptCaching = Some(true),
      supportsReasoning = Some(true),
      supportsResponseSchema = Some(true),
      supportsSystemMessages = Some(true)
    )
  )

  val textOnly: ModelMetadata = model("plain", ModelCapabilities(supportsSystemMessages = Some(false)))

  val registryModels: List[ModelMetadata] = List(everything, textOnly)

  val registry: ModelRegistryService = ModelRegistryService.fromModels(registryModels)
}
