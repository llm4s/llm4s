package org.llm4s.model

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelRegistryLookupSpec extends AnyFlatSpec with Matchers:

  private def makeModel(
    modelId: String,
    provider: String,
    mode: ModelMode,
    capabilities: ModelCapabilities = ModelCapabilities(),
    deprecationDate: Option[java.time.LocalDate] = None
  ): ModelMetadata =
    ModelMetadata(
      modelId = modelId,
      provider = provider,
      mode = mode,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(1024),
      capabilities = capabilities,
      pricing = ModelPricing(),
      deprecationDate = deprecationDate
    )

  private val modelA = makeModel(
    modelId = "acme/model-a",
    provider = "acme",
    mode = ModelMode.Chat,
    capabilities = ModelCapabilities(supportsVision = Some(true))
  )

  private val modelAPro = makeModel(
    modelId = "acme/model-a-pro",
    provider = "acme",
    mode = ModelMode.Chat
  )

  private val modelB = makeModel(
    modelId = "model-b",
    provider = "acme",
    mode = ModelMode.Embedding
  )

  private val modelC = makeModel(
    modelId = "image-c",
    provider = "openai",
    mode = ModelMode.ImageGeneration,
    deprecationDate = Some(java.time.LocalDate.parse("2000-01-01"))
  )

  private val modelD = makeModel(
    modelId = "zebra/model-d",
    provider = "zebra",
    mode = ModelMode.Chat
  )

  private val registry = ModelRegistryService.fromModels(List(modelA, modelAPro, modelB, modelC, modelD))

  "ModelRegistryService" should "return Left with ValidationError for unknown capability" in:
    registry.findByCapability("unsupported_capability") match
      case Left(err: ValidationError) =>
        err.message should include("No models found with capability: unsupported_capability")
      case other =>
        fail(s"Expected Left(ValidationError), got $other")

  it should "return Left with ValidationError for mode with no models" in:
    registry.listByMode(ModelMode.Completion) match
      case Left(err: ValidationError) =>
        err.message should include("No models found for mode: completion")
      case other =>
        fail(s"Expected Left(ValidationError), got $other")

  it should "return Left with ValidationError for unknown provider" in:
    registry.listByProvider("nonexistent-provider") match
      case Left(err: ValidationError) =>
        err.message should include("No models found for provider: nonexistent-provider")
      case other =>
        fail(s"Expected Left(ValidationError), got $other")

  it should "match listByProvider case-insensitively and return listProviders sorted without duplicates" in:
    registry.listByProvider("OPENAI") shouldBe registry.listByProvider("openai")
    registry.listProviders() shouldBe List("acme", "openai", "zebra")

  it should "resolve lookup with whitespace, case differences, and provider prefixes" in:
    registry.lookup("  acme/model-a  ") shouldBe Right(modelA)
    registry.lookup("ACME/MODEL-A") shouldBe Right(modelA)
    registry.lookup("acme/model-b") shouldBe Right(modelB)

  it should "return Left with ValidationError for model id that matches nothing" in:
    registry.lookup("unknown-model-xyz") match
      case Left(err: ValidationError) =>
        err.message should include("Model not found: unknown-model-xyz")
      case other =>
        fail(s"Expected Left(ValidationError), got $other")

  it should "lookup by provider and modelName for prefixed and plain IDs" in:
    registry.lookup("acme", "model-a") shouldBe Right(modelA)
    registry.lookup("acme", "model-b") shouldBe Right(modelB)

  it should "return Left with ValidationError when lookup by provider and modelName finds nothing" in:
    registry.lookup("acme", "nonexistent") match
      case Left(err: ValidationError) =>
        err.message should include("Model not found: acme/nonexistent")
      case other =>
        fail(s"Expected Left(ValidationError), got $other")
