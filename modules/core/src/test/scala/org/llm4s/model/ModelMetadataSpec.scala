package org.llm4s.model

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelMetadataSpec extends AnyFlatSpec with Matchers {

  "ModelMetadata" should "parse from JSON correctly" in {
    val json = ujson.Obj(
      "litellm_provider"          -> "openai",
      "mode"                      -> "chat",
      "max_input_tokens"          -> 128000,
      "max_output_tokens"         -> 16384,
      "input_cost_per_token"      -> 2.5e-6,
      "output_cost_per_token"     -> 1e-5,
      "supports_function_calling" -> true,
      "supports_vision"           -> true
    )

    val result = ModelMetadata.fromJson(ModelName("gpt-4o"), json)

    result.isRight shouldBe true
    val metadata = result.getOrElse(fail("Failed to parse metadata"))

    metadata.modelId shouldBe ModelName("gpt-4o")
    metadata.provider shouldBe ProviderId("openai")
    metadata.mode shouldBe ModelMode.Chat
    metadata.maxInputTokens shouldBe Some(128000)
    metadata.maxOutputTokens shouldBe Some(16384)
    metadata.pricing.inputCostPerToken shouldBe Some(2.5e-6)
    metadata.pricing.outputCostPerToken shouldBe Some(1e-5)
  }

  it should "handle missing optional fields" in {
    val json = ujson.Obj(
      "litellm_provider" -> "custom",
      "mode"             -> "chat"
    )

    val result = ModelMetadata.fromJson(ModelName("custom-model"), json)

    result.isRight shouldBe true
    val metadata = result.getOrElse(fail("Failed to parse metadata"))

    metadata.modelId shouldBe ModelName("custom-model")
    metadata.provider shouldBe ProviderId("custom")
    metadata.maxInputTokens shouldBe None
    metadata.maxOutputTokens shouldBe None
  }

  it should "correctly identify context window" in {
    val metadata = ModelMetadata(
      modelId = ModelName("test-model"),
      provider = ProviderId("test"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(100000),
      maxOutputTokens = Some(4096),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    metadata.contextWindow shouldBe Some(100000)
  }

  it should "fall back to maxOutputTokens for context window" in {
    val metadata = ModelMetadata(
      modelId = ModelName("test-model"),
      provider = ProviderId("test"),
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = Some(4096),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    metadata.contextWindow shouldBe Some(4096)
  }

  it should "check capabilities correctly" in {
    val capabilities = ModelCapabilities(
      supportsFunctionCalling = Some(true),
      supportsVision = Some(true),
      supportsPromptCaching = Some(false)
    )

    val metadata = ModelMetadata(
      modelId = ModelName("test-model"),
      provider = ProviderId("test"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(100000),
      maxOutputTokens = Some(4096),
      capabilities = capabilities,
      pricing = ModelPricing(),
      deprecationDate = None
    )

    metadata.supports("function_calling") shouldBe true
    metadata.supports("tools") shouldBe true // alias
    metadata.supports("vision") shouldBe true
    metadata.supports("images") shouldBe true // alias
    metadata.supports("caching") shouldBe false
    metadata.supports("unsupported") shouldBe false
  }

  it should "detect deprecated models" in {
    val pastDate   = java.time.LocalDate.now().minusDays(1)
    val futureDate = java.time.LocalDate.now().plusDays(1)

    val deprecatedModel = ModelMetadata(
      modelId = ModelName("deprecated-model"),
      provider = ProviderId("test"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = Some(pastDate)
    )

    val currentModel = ModelMetadata(
      modelId = ModelName("current-model"),
      provider = ProviderId("test"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = Some(futureDate)
    )

    deprecatedModel.isDeprecated shouldBe true
    currentModel.isDeprecated shouldBe false
  }

  it should "generate a readable description" in {
    val metadata = ModelMetadata(
      modelId = ModelName("gpt-4o"),
      provider = ProviderId("openai"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(128000),
      maxOutputTokens = Some(16384),
      capabilities = ModelCapabilities(
        supportsFunctionCalling = Some(true),
        supportsVision = Some(true)
      ),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    val description = metadata.description
    description should include("gpt-4o")
    description should include("openai")
    description should include("chat")
    description should include("128K")
  }

  "ModelMode" should "parse from string correctly" in {
    ModelMode.fromString("chat") shouldBe ModelMode.Chat
    ModelMode.fromString("embedding") shouldBe ModelMode.Embedding
    ModelMode.fromString("image_generation") shouldBe ModelMode.ImageGeneration
    ModelMode.fromString("unknown_mode") shouldBe ModelMode.Unknown
  }

  "ModelPricing" should "estimate cost correctly" in {
    val pricing = ModelPricing(
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1e-5)
    )

    val cost = pricing.estimateCost(inputTokens = 1000, outputTokens = 500)
    cost shouldBe Some(0.0075) // (1000 * 2.5e-6) + (500 * 1e-5) = 0.0025 + 0.005 = 0.0075
  }

  it should "return None for missing pricing data" in {
    val pricing = ModelPricing()

    pricing.estimateCost(1000, 500) shouldBe None
  }

  "ModelMode.fromString" should "read responses" in {
    ModelMode.fromString("responses") shouldBe ModelMode.Responses
    ModelMode.fromString("RESPONSES") shouldBe ModelMode.Responses
    ModelMode.Responses.name shouldBe "responses"
    upickle.default.read[ModelMode](upickle.default.write[ModelMode](ModelMode.Responses)) shouldBe ModelMode.Responses
  }

  private def fromJson(json: String): ModelMetadata =
    ModelMetadata.fromJson(ModelName("m"), ujson.read(json)).fold(e => fail(e.message), identity)

  "ModelMetadata.fromJson" should "read supported_endpoints, and none when the entry lists none" in {
    fromJson(
      """{"litellm_provider":"openai","mode":"chat","supported_endpoints":["/v1/chat/completions","/v1/responses"]}"""
    ).supportedEndpoints shouldBe Seq("/v1/chat/completions", "/v1/responses")
    fromJson("""{"litellm_provider":"openai","mode":"chat"}""").supportedEndpoints shouldBe Seq.empty
    fromJson("""{"litellm_provider":"openai","supported_endpoints":null}""").supportedEndpoints shouldBe Seq.empty
    fromJson(
      """{"litellm_provider":"openai","supported_endpoints":"/v1/responses"}"""
    ).supportedEndpoints shouldBe Seq.empty
  }

  it should "read a responses-only entry" in {
    val meta = fromJson(
      """{"litellm_provider":"openai","mode":"responses","supported_endpoints":["/v1/responses"]}"""
    )
    meta.mode shouldBe ModelMode.Responses
    meta.supportedEndpoints shouldBe Seq("/v1/responses")
  }

  "The bundled registry" should "mark the responses-only OpenAI models and know which endpoints serve the current ones" in {
    val service = ModelRegistryTestSupport.defaultServiceResult().fold(e => fail(e.message), identity)
    def lookup(id: String): ModelMetadata = service.lookup(ModelName(id)).fold(e => fail(e.message), identity)
    lookup("o1-pro").mode shouldBe ModelMode.Responses
    lookup("gpt-6.1-terra").mode shouldBe ModelMode.Chat
    lookup("gpt-6.1-terra").supportedEndpoints should contain("/v1/responses")
    lookup("gpt-audio-1.5").supportedEndpoints should not contain "/v1/responses"
  }

  "ModelMetadata's stored form" should "read an entry written before supportedEndpoints existed" in {
    // as 1.0.0's writer produced it
    val literal = """{"modelId":"gpt-4o","provider":"openai","maxInputTokens":128000,"maxOutputTokens":16384}"""
    val read    = upickle.default.read[ModelMetadata](literal)
    read.supportedEndpoints shouldBe Seq.empty
    read.maxInputTokens shouldBe Some(128000)
  }

  it should "still be built by the 8-argument apply" in {
    ModelMetadata(
      ModelName("m"),
      ProviderId("openai"),
      ModelMode.Chat,
      None,
      None,
      ModelCapabilities(),
      ModelPricing(),
      None
    ).supportedEndpoints shouldBe Seq.empty
  }

  it should "set supportedEndpoints" in {
    ModelMetadata(ModelName("m"), ProviderId("openai"))
      .withSupportedEndpoints(Seq("/v1/responses"))
      .supportedEndpoints shouldBe Seq("/v1/responses")
  }
}
