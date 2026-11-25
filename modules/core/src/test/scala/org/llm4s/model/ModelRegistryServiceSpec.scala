package org.llm4s.model

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.file.Files

class ModelRegistryServiceSpec extends AnyFlatSpec with Matchers:

  "ModelRegistryService" should "load the default registry successfully" in:
    defaultServiceResult().isRight shouldBe true

  it should "lookup models by exact ID" in:
    defaultServiceResult().flatMap(_.lookup(ModelName("gpt-4o"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe ModelName("gpt-4o")
        metadata.provider shouldBe ProviderId("openai")

  it should "lookup models case-insensitively" in:
    defaultServiceResult().flatMap(_.lookup(ModelName("GPT-4O"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe ModelName("gpt-4o")

  it should "lookup models with provider prefix" in:
    defaultServiceResult().flatMap(_.lookup(ModelName("openai/gpt-4o"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe ModelName("gpt-4o")

  it should "lookup models by provider and name" in:
    defaultServiceResult().flatMap(_.lookup(ProviderId("openai"), ModelName("gpt-4o"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe ModelName("gpt-4o")
        metadata.provider shouldBe ProviderId("openai")

  it should "apply embedded overrides so gemini/gemini-1.5-flash stays a chat model" in:
    defaultServiceResult().flatMap(_.lookup(ModelName("gemini/gemini-1.5-flash"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.provider shouldBe ProviderId("gemini")
        metadata.mode shouldBe ModelMode.Chat
        metadata.contextWindow shouldBe Some(1048576)
        metadata.reserveCompletion shouldBe Some(8192)

  it should "apply embedded overrides so claude-3-7-sonnet-latest resolves to Anthropic metadata" in:
    defaultServiceResult().flatMap(_.lookup(ModelName("claude-3-7-sonnet-latest"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.provider shouldBe ProviderId("anthropic")
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
      .fromConfig(ModelRegistryConfig.default.withUrl(tempFile.toUri.toString))
      .flatMap(_.lookup(ModelName("custom-url-model"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe ModelName("custom-url-model")
        metadata.provider shouldBe ProviderId("custom")

  it should "return error for unknown model" in:
    defaultServiceResult().flatMap(_.lookup(ModelName("unknown-model-xyz"))) match
      case Left(_)  => succeed
      case Right(_) => fail("Expected error for unknown model")

  it should "list models by provider" in:
    defaultServiceResult().flatMap(_.listByProvider(ProviderId("openai"))) match
      case Left(error) =>
        fail(error.message)
      case Right(models) =>
        models should not be empty
        models.foreach(_.provider shouldBe ProviderId("openai"))

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
    defaultServiceResult().map(_.listProviders()) match
      case Left(error) =>
        fail(error.message)
      case Right(providers) =>
        providers.isEmpty shouldBe false
        providers should contain("openai")
        providers should contain("anthropic")

  it should "create a registry from custom models" in:
    val customModel = ModelMetadata(
      modelId = ModelName("custom-model-123"),
      provider = ProviderId("custom"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(10000),
      maxOutputTokens = Some(2000),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    ModelRegistryService.fromModels(List(customModel)).lookup(ModelName("custom-model-123")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.modelId shouldBe ModelName("custom-model-123")
        metadata.provider shouldBe ProviderId("custom")

  it should "allow later models to override earlier models in a snapshot" in:
    val embeddedModel = defaultService().lookup(ModelName("gpt-4o")).toOption.get
    val overrideModel = embeddedModel.withMaxInputTokens(Some(999999))

    val service = ModelRegistryService.fromModels(List(embeddedModel, overrideModel))

    service.lookup(ModelName("gpt-4o")) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.maxInputTokens shouldBe Some(999999)

  it should "create a registry from multiple custom models" in:
    val customModels = List(
      ModelMetadata(
        modelId = ModelName("custom-1"),
        provider = ProviderId("custom"),
        mode = ModelMode.Chat,
        maxInputTokens = Some(10000),
        maxOutputTokens = Some(2000),
        capabilities = ModelCapabilities(),
        pricing = ModelPricing(),
        deprecationDate = None
      ),
      ModelMetadata(
        modelId = ModelName("custom-2"),
        provider = ProviderId("custom"),
        mode = ModelMode.Embedding,
        maxInputTokens = Some(8192),
        maxOutputTokens = Some(8192),
        capabilities = ModelCapabilities(),
        pricing = ModelPricing(),
        deprecationDate = None
      )
    )

    val service = ModelRegistryService.fromModels(customModels)
    service.lookup(ModelName("custom-1")).isRight shouldBe true
    service.lookup(ModelName("custom-2")).isRight shouldBe true

  it should "not resolve a partial model name, which only search finds" in:
    val customModel = ModelMetadata(
      modelId = ModelName("my-unique-test-model-xyz"),
      provider = ProviderId("custom"),
      mode = ModelMode.Chat,
      maxInputTokens = Some(10000),
      maxOutputTokens = Some(2000),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    val service = ModelRegistryService.fromModels(List(customModel))
    service.lookup(ModelName("unique-test")).isLeft shouldBe true
    service.search("unique-test").map(_.modelId) shouldBe List("my-unique-test-model-xyz")

  it should "not resolve a name several ids contain" in:
    val service = ModelRegistryService.fromModels(
      List(
        ModelMetadata(
          modelId = ModelName("test-model-a"),
          provider = ProviderId("custom"),
          mode = ModelMode.Chat,
          maxInputTokens = Some(10000),
          maxOutputTokens = Some(2000),
          capabilities = ModelCapabilities(),
          pricing = ModelPricing(),
          deprecationDate = None
        ),
        ModelMetadata(
          modelId = ModelName("test-model-b"),
          provider = ProviderId("custom"),
          mode = ModelMode.Chat,
          maxInputTokens = Some(10000),
          maxOutputTokens = Some(2000),
          capabilities = ModelCapabilities(),
          pricing = ModelPricing(),
          deprecationDate = None
        )
      )
    )

    service.lookup(ModelName("test-model")).isLeft shouldBe true

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

    ModelRegistryService.fromJsonString(customJson).flatMap(_.lookup(ModelName("custom-json-model"))) match
      case Left(error) =>
        fail(error.message)
      case Right(metadata) =>
        metadata.provider shouldBe ProviderId("custom")
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

    for id <- List(
        "claude-opus-4-7",
        "claude-opus-4-7-20260416",
        "claude-opus-4-8",
        "claude-fable-5",
        "claude-fable-5-1",
        "claude-fable-5-2"
      )
    do
      val metadata = service.lookup(ModelName(id)).toOption.get
      metadata.capabilities.supportsBudgetThinking shouldBe Some(false)
      metadata.provider shouldBe ProviderId("anthropic")
      metadata.pricing.inputCostPerToken shouldBe raw(id).obj.get("input_cost_per_token").map(_.num)
      metadata.capabilities.supportsAdaptiveThinking shouldBe Some(true)

    service
      .lookup(ModelName("us.anthropic.claude-opus-4-7"))
      .toOption
      .get
      .capabilities
      .supportsBudgetThinking shouldBe Some(false)
    service
      .lookup(ModelName("vertex_ai/claude-fable-5"))
      .toOption
      .get
      .capabilities
      .supportsBudgetThinking shouldBe Some(false)

  it should "mark every member of a patched family on the vendor's own routes, Bedrock regions included" in:
    val service = defaultService()
    val raw = ujson
      .read(
        scala.io.Source
          .fromInputStream(getClass.getResourceAsStream(ModelRegistryConfig.DefaultResourcePath), "UTF-8")
          .mkString
      )
      .obj
    // every model Anthropic's API answers `thinking.type.enabled` with a 400: Opus from 4.7 and the Claude 5 models
    val family =
      """.*claude-(opus-(4[-.][78]|5([-.]5)?)|sonnet-5([-.]5)?|haiku-5[-.]5|(fable|mythos)-5([-.][12])?)([-.]fast)?(@default)?""".r
    val routes = Set("anthropic", "bedrock_converse", "azure_ai", "vertex_ai-anthropic_models", "openrouter")
    val members = raw.collect {
      case (id, entry)
          if family.matches(id) &&
            (entry.obj.get("litellm_provider").exists(p => routes.contains(p.str)) ||
              id.matches("bedrock/us-gov-(east|west)-1/anthropic\\..*")) =>
        id
    }.toList
    members should not be empty
    (members should contain).allOf(
      "apac.anthropic.claude-opus-4-8",
      "us-gov.anthropic.claude-fable-5-2",
      "claude-sonnet-5-5",
      "claude-sonnet-5",
      "claude-haiku-5-5",
      "claude-opus-5",
      "claude-opus-5-5",
      "us.anthropic.claude-sonnet-5-5",
      "vertex_ai/claude-sonnet-5-5"
    )
    for id <- members do
      val caps = service.lookup(ModelName(id)).toOption.get.capabilities
      withClue(id)(caps.supportsBudgetThinking shouldBe Some(false))
      if id.contains("fable-5") then withClue(id)(caps.supportsThinkingOff shouldBe Some(false))

  it should "read the catalog's sampling flag: false for Opus 4.7 and the Claude 5 models, unknown before them" in:
    val service = defaultService()
    for id <- List("claude-opus-4-7", "claude-sonnet-5-5", "claude-haiku-5-5", "us.anthropic.claude-sonnet-5-5") do
      withClue(id)(service.lookup(ModelName(id)).toOption.get.capabilities.supportsSamplingParams shouldBe Some(false))
    for id <- List("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5") do
      withClue(id)(service.lookup(ModelName(id)).toOption.get.capabilities.supportsSamplingParams shouldBe None)

  it should "know from the catalog which Claude models cannot turn thinking off" in:
    val service = defaultService()
    for id <- List("claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-2") do
      withClue(id)(service.lookup(ModelName(id)).toOption.get.capabilities.supportsThinkingOff shouldBe Some(false))
    for id <- List("claude-haiku-5-5", "claude-opus-5", "claude-sonnet-4-6") do
      withClue(id)(
        service.lookup(ModelName(id)).toOption.get.capabilities.supportsThinkingOff should not be Some(false)
      )

  it should "leave a model that still takes a thinking budget unknown" in:
    val service = defaultService()
    for id <- List("claude-opus-4-6", "claude-sonnet-4-6", "claude-3-7-sonnet-latest") do
      service.lookup(ModelName(id)).toOption.get.capabilities.supportsBudgetThinking shouldBe None

  it should "read the embedded effort metadata" in:
    val sonnet = defaultService().lookup(ModelName("claude-sonnet-4-6")).toOption.get.capabilities
    sonnet.supportsOutputConfig shouldBe Some(true)
    sonnet.supportsAdaptiveThinking shouldBe Some(true)

  private def defaultServiceResult(): Result[ModelRegistryService] =
    ModelRegistryTestSupport.defaultServiceResult()

  private def defaultService(): ModelRegistryService =
    ModelRegistryTestSupport.defaultService()
