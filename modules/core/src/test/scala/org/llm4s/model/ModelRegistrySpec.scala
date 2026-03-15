package org.llm4s.model

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext.Implicits.global

class ModelRegistrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit =
    ModelRegistry.reset()

  "ModelRegistry" should "initialize successfully" in {
    val result = ModelRegistry.initialize()
    result.isRight shouldBe true
  }

  it should "lookup models by exact ID" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.lookup("gpt-4o")
    result.isRight shouldBe true

    val metadata = result.getOrElse(fail("Model not found"))
    metadata.modelId shouldBe "gpt-4o"
    metadata.provider shouldBe "openai"
  }

  it should "lookup models case-insensitively" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.lookup("GPT-4O")
    result.isRight shouldBe true

    val metadata = result.getOrElse(fail("Model not found"))
    metadata.modelId shouldBe "gpt-4o"
  }

  it should "lookup models with provider prefix" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.lookup("openai/gpt-4o")
    result.isRight shouldBe true

    val metadata = result.getOrElse(fail("Model not found"))
    metadata.modelId shouldBe "gpt-4o"
  }

  it should "lookup models by provider and name" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.lookup("openai", "gpt-4o")
    result.isRight shouldBe true

    val metadata = result.getOrElse(fail("Model not found"))
    metadata.modelId shouldBe "gpt-4o"
  }

  it should "return error for unknown model" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.lookup("unknown-model-xyz")
    result.isLeft shouldBe true
  }

  it should "list models by provider" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.listByProvider("openai")
    result.isRight shouldBe true

    val models = result.getOrElse(fail("No models found"))
    models should not be empty
    models.foreach(_.provider shouldBe "openai")
  }

  it should "list models by mode" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.listByMode(ModelMode.Chat)
    result.isRight shouldBe true

    val models = result.getOrElse(fail("No models found"))
    models should not be empty
    models.foreach(_.mode shouldBe ModelMode.Chat)
  }

  it should "find models by capability" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.findByCapability("vision")
    result.isRight shouldBe true

    val models = result.getOrElse(fail("No models found"))
    models should not be empty
    models.foreach(_.supports("vision") shouldBe true)
  }

  it should "list all providers" in {
    ModelRegistry.initialize()

    val result = ModelRegistry.listProviders()
    result.isRight shouldBe true

    val providers = result.getOrElse(fail("No providers found"))
    providers should not be empty
    providers should contain("openai")
    providers should contain("anthropic")
  }

  it should "register custom models" in {
    ModelRegistry.initialize()

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

    ModelRegistry.register(customModel)

    val result = ModelRegistry.lookup("custom-model-123")
    result.isRight shouldBe true

    val metadata = result.getOrElse(fail("Custom model not found"))
    metadata.modelId shouldBe "custom-model-123"
    metadata.provider shouldBe "custom"
  }

  it should "allow custom models to override embedded models" in {
    ModelRegistry.initialize()

    // Override an existing model with different context window
    val overrideModel = ModelMetadata(
      modelId = "gpt-4o",
      provider = "openai",
      mode = ModelMode.Chat,
      maxInputTokens = Some(999999), // Override with large value
      maxOutputTokens = Some(16384),
      inputCostPerToken = Some(2.5e-6),
      outputCostPerToken = Some(1e-5),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

    ModelRegistry.register(overrideModel)

    val result = ModelRegistry.lookup("gpt-4o")
    result.isRight shouldBe true

    val metadata = result.getOrElse(fail("Model not found"))
    metadata.maxInputTokens shouldBe Some(999999)
  }

  it should "provide statistics" in {
    ModelRegistry.initialize()

    val stats = ModelRegistry.statistics()

    (stats should contain).key("totalModels")
    (stats should contain).key("providers")
    (stats should contain).key("chatModels")
    (stats should contain).key("embeddingModels")

    stats("totalModels").asInstanceOf[Int] should be > 0
    stats("providers").asInstanceOf[Int] should be > 0
  }

  it should "register multiple custom models" in {
    ModelRegistry.initialize()

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

    ModelRegistry.registerAll(customModels)

    val result1 = ModelRegistry.lookup("custom-1")
    val result2 = ModelRegistry.lookup("custom-2")

    result1.isRight shouldBe true
    result2.isRight shouldBe true
  }

  it should "handle fuzzy matching" in {
    ModelRegistry.initialize()

    // Register a unique custom model for testing fuzzy match
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

    ModelRegistry.register(customModel)

    // Should find with partial match if unique
    val result = ModelRegistry.lookup("unique-test")
    result.isRight shouldBe true
  }

  it should "return error for ambiguous fuzzy matches" in {
    ModelRegistry.initialize()

    // Register multiple models with similar names
    val model1 = ModelMetadata(
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
    )

    val model2 = ModelMetadata(
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

    ModelRegistry.registerAll(List(model1, model2))

    // Should fail with ambiguous match error
    val result = ModelRegistry.lookup("test-model")
    result.isLeft shouldBe true
  }

  it should "load custom metadata from JSON string" in {
    ModelRegistry.initialize()

    val customJson = """{
      "custom-json-model": {
        "litellm_provider": "custom",
        "mode": "chat",
        "max_input_tokens": 50000,
        "max_output_tokens": 10000,
        "input_cost_per_token": 1e-6,
        "output_cost_per_token": 5e-6
      }
    }"""

    val result = ModelRegistry.loadCustomMetadataFromString(customJson)
    result.isRight shouldBe true

    val lookupResult = ModelRegistry.lookup("custom-json-model")
    lookupResult.isRight shouldBe true

    val metadata = lookupResult.getOrElse(fail("Custom JSON model not found"))
    metadata.provider shouldBe "custom"
    metadata.maxInputTokens shouldBe Some(50000)
  }

  it should "handle malformed JSON from URL" in {
    ModelRegistry.initialize()

    val badJson = "{ invalid json }"
    val dataUrl =
      "data:application/json," + java.net.URLEncoder.encode(badJson, "UTF-8")

    val result = ModelRegistry.updateFromUrl(dataUrl)

    result.isLeft shouldBe true
  }

  it should "preserve existing cache when update fails" in {
    ModelRegistry.initialize()

    val before = ModelRegistry.lookup("gpt-4o")
    before.isRight shouldBe true

    val badJson = "{ broken"
    val dataUrl =
      "data:application/json," + java.net.URLEncoder.encode(badJson, "UTF-8")

    ModelRegistry.updateFromUrl(dataUrl)

    val after = ModelRegistry.lookup("gpt-4o")
    after.isRight shouldBe true
  }

  it should "handle concurrent update calls safely" in {
    ModelRegistry.initialize()

    val json =
      """{
      "parallel-model": {
        "litellm_provider": "custom",
        "mode": "chat",
        "max_input_tokens": 1000,
        "max_output_tokens": 500
      }
    }"""

    val dataUrl =
      "data:application/json," + java.net.URLEncoder.encode(json, "UTF-8")

    val futures = (1 to 5).map { _ =>
      scala.concurrent.Future {
        ModelRegistry.updateFromUrl(dataUrl)
      }
    }

    scala.concurrent.Await.result(
      scala.concurrent.Future.sequence(futures),
      scala.concurrent.duration.Duration.Inf
    )

    val result = ModelRegistry.lookup("gpt-4o")
    result.isRight shouldBe true
  }

}
