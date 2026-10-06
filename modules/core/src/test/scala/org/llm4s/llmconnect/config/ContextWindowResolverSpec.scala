package org.llm4s.llmconnect.config

import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ContextWindowResolverSpec extends AnyFlatSpec with Matchers:

  private val defaultService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def resolver(service: ModelRegistryService): ContextWindowResolver =
    ContextWindowResolver(service)

  "ContextWindowResolver.resolve" should "use fallback when model not in registry" in {
    val fallback = (_: String) => (9999, 8888)
    val (cw, rc) = resolver(defaultService).resolve(
      lookupProviders = Seq("openai"),
      modelName = "nonexistent-model-xyz-12345",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 9999
    rc shouldBe 8888
  }

  it should "use fallback with multiple lookup providers when model not in registry" in {
    val fallback = (_: String) => (50000, 2000)
    val (cw, rc) = resolver(defaultService).resolve(
      lookupProviders = Seq("azure", "openai"),
      modelName = "another-unknown-model-999",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 50000
    rc shouldBe 2000
  }

  it should "use fallback when lookupProviders is empty and model not in registry" in {
    val fallback = (_: String) => (1000, 500)
    val (cw, rc) = resolver(defaultService).resolve(
      lookupProviders = Seq.empty,
      modelName = "nonexistent-model-xyz-12345",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 1000
    rc shouldBe 500
  }

  it should "use registry metadata when model is registered (hit path)" in {
    val testModel = ModelMetadata(
      modelId = "test-provider/test-resolver-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = Some(77000),
      maxOutputTokens = Some(3500),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )
    val service = ModelRegistryService.fromModels(List(testModel))

    val fallback = (_: String) => (9999, 9999)
    val (cw, rc) = resolver(service).resolve(
      lookupProviders = Seq("test-provider"),
      modelName = "test-resolver-model",
      defaultContextWindow = 8192,
      defaultReserve = 4096,
      fallbackResolver = fallback
    )
    cw shouldBe 77000
    rc shouldBe 3500
  }

  it should "use defaultContextWindow and defaultReserve when registry hit has no token data" in {
    val testModel = ModelMetadata(
      modelId = "test-provider/test-no-tokens-model",
      provider = "test-provider",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = None,
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )
    val service = ModelRegistryService.fromModels(List(testModel))

    val fallback = (_: String) => (9999, 9999)
    val (cw, rc) = resolver(service).resolve(
      lookupProviders = Seq("test-provider"),
      modelName = "test-no-tokens-model",
      defaultContextWindow = 12345,
      defaultReserve = 6789,
      fallbackResolver = fallback
    )
    cw shouldBe 12345
    rc shouldBe 6789
  }

  // ---------------------------------------------------------------------------------------------
  // strictContextWindow: the provider's own entry, exact id, nothing else
  // ---------------------------------------------------------------------------------------------

  private def entry(modelId: String, provider: String, maxInput: Option[Int]): ModelMetadata =
    ModelMetadata(
      modelId = modelId,
      provider = provider,
      mode = ModelMode.Chat,
      maxInputTokens = maxInput,
      maxOutputTokens = Some(4096),
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

  private def strict(entries: ModelMetadata*): ContextWindowResolver =
    resolver(ModelRegistryService.fromModels(entries.toList))

  "ContextWindowResolver.strictContextWindow" should "return the input limit of the provider's own provider/model entry" in {
    val r = strict(entry("groq/llama-3.1-8b-instant", "groq", Some(128000)))

    r.strictContextWindow("groq", "llama-3.1-8b-instant") shouldBe Some(128000)
  }

  it should "find a model whose id has a path of its own, as Together and Fireworks ids do" in {
    val r = strict(
      entry("together_ai/meta-llama/Llama-3.3-70B-Instruct-Turbo", "together_ai", Some(131072)),
      entry("fireworks_ai/accounts/fireworks/models/gpt-oss-120b", "fireworks_ai", Some(131072))
    )

    r.strictContextWindow("together_ai", "meta-llama/Llama-3.3-70B-Instruct-Turbo") shouldBe Some(131072)
    r.strictContextWindow("fireworks_ai", "accounts/fireworks/models/gpt-oss-120b") shouldBe Some(131072)
  }

  it should "find a bare id among the provider's own entries, as the registry keys deepseek-chat" in {
    val r = strict(entry("deepseek-chat", "deepseek", Some(131072)))

    r.strictContextWindow("deepseek", "deepseek-chat") shouldBe Some(131072)
  }

  it should "match ids and providers case-insensitively" in {
    val r = strict(entry("groq/Llama-3.1-8B-Instant", "groq", Some(128000)))

    r.strictContextWindow("GROQ", "llama-3.1-8b-instant") shouldBe Some(128000)
  }

  it should "not match a partial model name, which the registry's own lookup would" in {
    val r = strict(entry("groq/llama-3.1-8b-instant", "groq", Some(128000)))

    r.strictContextWindow("groq", "llama-3.1-8b") shouldBe None
    r.strictContextWindow("groq", "instant") shouldBe None
  }

  it should "not return another provider's entry for the same model name" in {
    val r = strict(
      entry("openrouter/some-model", "openrouter", Some(65536)),
      entry("gpt-4o", "openai", Some(128000))
    )

    r.strictContextWindow("groq", "some-model") shouldBe None
    r.strictContextWindow("groq", "gpt-4o") shouldBe None
  }

  it should "give no window for an entry that lists no input limit, or a non-positive one" in {
    val r = strict(
      entry("together_ai/no-limit", "together_ai", None),
      entry("together_ai/zero-limit", "together_ai", Some(0)),
      entry("together_ai/negative-limit", "together_ai", Some(-1))
    )

    r.strictContextWindow("together_ai", "no-limit") shouldBe None
    r.strictContextWindow("together_ai", "zero-limit") shouldBe None
    r.strictContextWindow("together_ai", "negative-limit") shouldBe None
  }

  it should "give no window for a provider the registry has no entries for" in {
    strict(entry("groq/llama-3.1-8b-instant", "groq", Some(128000)))
      .strictContextWindow("no-such-provider", "llama-3.1-8b-instant") shouldBe None
  }

  it should "read the bundled registry: Groq's gpt-oss-120b and Perplexity's sonar-pro" in {
    val r = resolver(defaultService)

    r.strictContextWindow("groq", "openai/gpt-oss-120b") shouldBe Some(131072)
    r.strictContextWindow("perplexity", "sonar-pro") shouldBe Some(200000)
    r.strictContextWindow("groq", "model-that-does-not-exist") shouldBe None
  }
