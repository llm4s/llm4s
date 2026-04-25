package org.llm4s.model

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.file.Files

class ModelRegistryServiceSpec extends AnyFlatSpec with Matchers:

  "ModelRegistryService" should "load the default registry successfully" in:
    defaultServiceResult().isRight shouldBe true

  it should "lookup models by exact ID" in:
    defaultServiceResult().flatMap(_.lookup("gpt-4o")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe "gpt-4o"
        metadata.provider shouldBe "openai"

  it should "lookup models case-insensitively" in:
    defaultServiceResult().flatMap(_.lookup("GPT-4O")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe "gpt-4o"

  it should "lookup models with provider prefix" in:
    defaultServiceResult().flatMap(_.lookup("openai/gpt-4o")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe "gpt-4o"

  it should "lookup models by provider and name" in:
    defaultServiceResult().flatMap(_.lookup("openai", "gpt-4o")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe "gpt-4o"
        metadata.provider shouldBe "openai"

  it should "load a registry service from a configured URL" in:
    val json =
      """{
        |  "custom-url-model": {
        |    "litellm_provider": "custom",
        |    "mode": "chat",
        |    "max_input_tokens": 50000,
        |    "max_output_tokens": 10000
        |  }
        |}""".stripMargin

    val tempFile = Files.createTempFile("model-registry-url-", ".json")
    Files.writeString(tempFile, json)
    tempFile.toFile.deleteOnExit()

    ModelRegistryService
      .fromConfig(ModelRegistryConfig(resourcePath = None, filePath = None, url = Some(tempFile.toUri.toString)))
      .flatMap(_.lookup("custom-url-model")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe "custom-url-model"
        metadata.provider shouldBe "custom"

  it should "reject model registry configs with multiple sources" in:
    val result = ModelRegistryService.fromConfig(
      ModelRegistryConfig(
        resourcePath = Some(ModelRegistryConfig.DefaultResourcePath),
        filePath = Some("/tmp/models.json"),
        url = None
      )
    )

    result.isLeft shouldBe true

  it should "return error for unknown model" in:
    defaultServiceResult().flatMap(_.lookup("unknown-model-xyz")) match
      case Left(_)  => succeed
      case Right(_) => fail("Expected error for unknown model")

  it should "list models by provider" in:
    defaultServiceResult().flatMap(_.listByProvider("openai")) match
      case Left(error) =>
        fail(error.message)
      case Right(models) =>
        models should not be empty
        models.foreach(_.provider shouldBe "openai")

  it should "list models by mode" in:
    defaultServiceResult().flatMap(_.listByMode(ModelMode.Chat)) match
      case Left(error) =>
        fail(error.message)
      case Right(models) =>
        models should not be empty
        models.foreach(_.mode shouldBe ModelMode.Chat)

  it should "find models by capability" in:
    defaultServiceResult().flatMap(_.findByCapability("vision")) match
      case Left(error) =>
        fail(error.message)
      case Right(models) =>
        models should not be empty
        models.foreach(_.supports("vision") shouldBe true)

  it should "list all providers" in:
    defaultServiceResult().flatMap(_.listProviders()) match
      case Left(error) =>
        fail(error.message)
      case Right(providers) =>
        providers should not be empty
        providers should contain("openai")
        providers should contain("anthropic")

  it should "provide statistics" in:
    defaultServiceResult().flatMap(_.statistics()) match
      case Left(error) =>
        fail(error.message)
      case Right(stats) =>
        (stats should contain).key("totalModels")
        (stats should contain).key("providers")
        (stats should contain).key("chatModels")
        (stats should contain).key("embeddingModels")
        stats("totalModels").asInstanceOf[Int] should be > 0
        stats("providers").asInstanceOf[Int] should be > 0

  it should "create a registry from custom models" in:
    val customModel = ModelMetadata(
      modelId = "custom-model-123",
      provider = "custom",
      mode = ModelMode.Chat,
      maxInputTokens = Some(10000),
      maxOutputTokens = Some(2000),
      inputCostPerToken = Some(1e-6),
      outputCostPerToken = Some(5e-6),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    ModelRegistryService.fromModels(List(customModel)).lookup("custom-model-123") match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe "custom-model-123"
        metadata.provider shouldBe "custom"

  it should "allow later models to override earlier models in a snapshot" in:
    val embeddedModel = defaultService().lookup("gpt-4o").toOption.get
    val overrideModel = embeddedModel.copy(maxInputTokens = Some(999999))

    val service = ModelRegistryService.fromModels(List(embeddedModel, overrideModel))

    service.lookup("gpt-4o") match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.maxInputTokens shouldBe Some(999999)

  it should "create a registry from multiple custom models" in:
    val customModels = List(
      ModelMetadata(
        modelId = "custom-1",
        provider = "custom",
        mode = ModelMode.Chat,
        maxInputTokens = Some(10000),
        maxOutputTokens = Some(2000),
        inputCostPerToken = None,
        outputCostPerToken = None,
        capabilities = ModelCapabilities(),
        pricing = ModelPricing(),
        deprecationDate = None
      ),
      ModelMetadata(
        modelId = "custom-2",
        provider = "custom",
        mode = ModelMode.Embedding,
        maxInputTokens = Some(8192),
        maxOutputTokens = Some(8192),
        inputCostPerToken = None,
        outputCostPerToken = None,
        capabilities = ModelCapabilities(),
        pricing = ModelPricing(),
        deprecationDate = None
      )
    )

    val service = ModelRegistryService.fromModels(customModels)
    service.lookup("custom-1").isRight shouldBe true
    service.lookup("custom-2").isRight shouldBe true

  it should "handle fuzzy matching for a unique partial model name" in:
    val customModel = ModelMetadata(
      modelId = "my-unique-test-model-xyz",
      provider = "custom",
      mode = ModelMode.Chat,
      maxInputTokens = Some(10000),
      maxOutputTokens = Some(2000),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    val service = ModelRegistryService.fromModels(List(customModel))
    service.lookup("unique-test").isRight shouldBe true

  it should "return error for ambiguous fuzzy matches" in:
    val service = ModelRegistryService.fromModels(
      List(
        ModelMetadata(
          modelId = "test-model-a",
          provider = "custom",
          mode = ModelMode.Chat,
          maxInputTokens = Some(10000),
          maxOutputTokens = Some(2000),
          inputCostPerToken = None,
          outputCostPerToken = None,
          capabilities = ModelCapabilities(),
          pricing = ModelPricing(),
          deprecationDate = None
        ),
        ModelMetadata(
          modelId = "test-model-b",
          provider = "custom",
          mode = ModelMode.Chat,
          maxInputTokens = Some(10000),
          maxOutputTokens = Some(2000),
          inputCostPerToken = None,
          outputCostPerToken = None,
          capabilities = ModelCapabilities(),
          pricing = ModelPricing(),
          deprecationDate = None
        )
      )
    )

    service.lookup("test-model").isLeft shouldBe true

  it should "load custom metadata from a JSON string" in:
    val customJson =
      """{
        |  "custom-json-model": {
        |    "litellm_provider": "custom",
        |    "mode": "chat",
        |    "max_input_tokens": 50000,
        |    "max_output_tokens": 10000,
        |    "input_cost_per_token": 1e-6,
        |    "output_cost_per_token": 5e-6
        |  }
        |}""".stripMargin

    ModelRegistryService.fromJsonString(customJson).flatMap(_.lookup("custom-json-model")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.provider shouldBe "custom"
        metadata.maxInputTokens shouldBe Some(50000)

  it should "return an error for malformed JSON from a URL source" in:
    val badJson = "{ invalid json }"
    val dataUrl = s"data:application/json,${URLEncoder.encode(badJson, "UTF-8")}"

    ModelRegistryService.fromUrl(dataUrl).isLeft shouldBe true

  private def defaultServiceResult(): Result[ModelRegistryService] =
    ModelRegistryTestSupport.defaultServiceResult()

  private def defaultService(): ModelRegistryService =
    ModelRegistryTestSupport.defaultService()
