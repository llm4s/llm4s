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

  it should "apply embedded overrides so gemini/gemini-1.5-flash stays a chat model" in:
    defaultServiceResult().flatMap(_.lookup("gemini/gemini-1.5-flash")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.provider shouldBe "gemini"
        metadata.mode shouldBe ModelMode.Chat
        metadata.contextWindow shouldBe Some(1048576)
        metadata.reserveCompletion shouldBe Some(8192)

  it should "apply embedded overrides so claude-3-7-sonnet-latest resolves to Anthropic metadata" in:
    defaultServiceResult().flatMap(_.lookup("claude-3-7-sonnet-latest")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.provider shouldBe "anthropic"
        metadata.mode shouldBe ModelMode.Chat
        metadata.contextWindow shouldBe Some(200000)

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
    val overrideModel = embeddedModel.withMaxInputTokens(Some(999999))

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

  it should "let override entries take precedence when merging the embedded overrides" in:
    val base =
      ujson.read("""{ "gemini/gemini-1.5-flash": { "litellm_provider": "gemini", "mode": "embedding" } }""").obj

    val merged = ModelRegistryService.mergeOverrides(
      ujson.Obj.from(base),
      ModelRegistryConfig.DefaultOverridesResourcePath
    )
    merged("gemini/gemini-1.5-flash")("mode").str shouldBe "chat"
    merged.value.keySet should contain("claude-3-7-sonnet-latest")

  it should "fall back to the base snapshot when the overrides resource is missing" in:
    val base = ujson.Obj("base-only-model" -> ujson.Obj("litellm_provider" -> "custom", "mode" -> "chat"))

    ModelRegistryService.mergeOverrides(base, "/modeldata/does-not-exist.json") shouldBe base

  it should "replace an entry whole when the override is not a patch, dropping keys it leaves out" in:
    val base        = ujson.Obj("m" -> ujson.Obj("mode" -> "embedding", "output_vector_size" -> 768))
    val replacement = ujson.Obj("m" -> ujson.Obj("mode" -> "chat"))

    ModelRegistryService.applyOverrides(base, replacement) shouldBe ujson.Obj("m" -> ujson.Obj("mode" -> "chat"))

  it should "set only the listed keys when the override is a patch, keeping the rest of the entry" in:
    val base = ujson.Obj("m" -> ujson.Obj("mode" -> "chat", "input_cost_per_token" -> 5e-6))
    val patch =
      ujson.Obj("m" -> ujson.Obj(ModelRegistryService.PatchKey -> true, "supports_budget_thinking" -> false))

    ModelRegistryService.applyOverrides(base, patch) shouldBe
      ujson.Obj("m" -> ujson.Obj("mode" -> "chat", "input_cost_per_token" -> 5e-6, "supports_budget_thinking" -> false))

  it should "skip a patch for a model the base does not have" in:
    val base = ujson.Obj("m" -> ujson.Obj("mode" -> "chat"))
    val patch =
      ujson.Obj("other" -> ujson.Obj(ModelRegistryService.PatchKey -> true, "supports_budget_thinking" -> false))

    ModelRegistryService.applyOverrides(base, patch) shouldBe base

  it should "treat an entry whose patch marker is false as a replacement" in:
    val base        = ujson.Obj("m" -> ujson.Obj("mode" -> "chat", "max_tokens" -> 10))
    val replacement = ujson.Obj("m" -> ujson.Obj(ModelRegistryService.PatchKey -> false, "mode" -> "chat"))

    ModelRegistryService.applyOverrides(base, replacement) shouldBe replacement

  it should "not change the base snapshot it patches" in:
    val base  = ujson.Obj("m" -> ujson.Obj("mode" -> "chat"))
    val patch = ujson.Obj("m" -> ujson.Obj(ModelRegistryService.PatchKey -> true, "supports_budget_thinking" -> false))

    ModelRegistryService.applyOverrides(base, patch)
    base shouldBe ujson.Obj("m" -> ujson.Obj("mode" -> "chat"))

  it should "name only models the embedded snapshot has in the overrides file's patches" in:
    def read(path: String) =
      ujson.read(scala.io.Source.fromInputStream(getClass.getResourceAsStream(path), "UTF-8").mkString).obj
    val base      = read(ModelRegistryConfig.DefaultResourcePath)
    val overrides = read(ModelRegistryConfig.DefaultOverridesResourcePath)
    val patched = overrides.value.collect {
      case (id, entry: ujson.Obj) if entry.value.get(ModelRegistryService.PatchKey).contains(ujson.True) => id
    }

    patched should not be empty
    patched.filterNot(base.value.contains) shouldBe empty

  it should "mark the embedded models that reject a thinking budget, keeping their litellm prices" in:
    val service = defaultService()
    val raw = ujson
      .read(
        scala.io.Source
          .fromInputStream(getClass.getResourceAsStream(ModelRegistryConfig.DefaultResourcePath), "UTF-8")
          .mkString
      )
      .obj

    for id <- List("claude-opus-4-7", "claude-opus-4-7-20260416", "claude-opus-4-8", "claude-fable-5") do
      val metadata = service.lookup(id).toOption.get
      metadata.capabilities.supportsBudgetThinking shouldBe Some(false)
      metadata.provider shouldBe "anthropic"
      metadata.inputCostPerToken shouldBe raw(id).obj.get("input_cost_per_token").map(_.num)
      metadata.capabilities.supportsAdaptiveThinking shouldBe Some(true)

    service.lookup("us.anthropic.claude-opus-4-7").toOption.get.capabilities.supportsBudgetThinking shouldBe Some(false)
    service.lookup("vertex_ai/claude-fable-5").toOption.get.capabilities.supportsBudgetThinking shouldBe Some(false)

  it should "leave a model that still takes a thinking budget unknown" in:
    val service = defaultService()
    for id <- List("claude-opus-4-6", "claude-sonnet-4-6", "claude-3-7-sonnet-latest") do
      service.lookup(id).toOption.get.capabilities.supportsBudgetThinking shouldBe None

  it should "read the embedded effort metadata" in:
    val sonnet = defaultService().lookup("claude-sonnet-4-6").toOption.get.capabilities
    sonnet.supportsOutputConfig shouldBe Some(true)
    sonnet.supportsAdaptiveThinking shouldBe Some(true)

  private def defaultServiceResult(): Result[ModelRegistryService] =
    ModelRegistryTestSupport.defaultServiceResult()

  private def defaultService(): ModelRegistryService =
    ModelRegistryTestSupport.defaultService()
