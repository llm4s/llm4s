package org.llm4s.model

import org.llm4s.llmconnect.model.{ CompletionOptions, ResponseFormat, SystemMessage, UserMessage }
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RequestTransformerSpec extends AnyFunSuite with Matchers with EitherValues {

  // A fixture vendor rule, applied through `adjusted`, standing in for a provider module's own
  // rules (llm4s-openai's o-series constraints are tested in its OpenAIModelRulesSpec): models
  // named like the o-series get a constrained capability set, as a provider module would supply.
  private val constrained = ModelCapabilities(
    supportsReasoning = Some(true),
    supportsNativeStreaming = Some(false),
    supportsSystemMessages = Some(false),
    temperatureConstraint = Some((1.0, 1.0)),
    disallowedParams = Some(Set("top_p", "presence_penalty", "frequency_penalty", "logprobs"))
  )

  private def isConstrained(modelId: String): Boolean = {
    val m = modelId.toLowerCase
    m.startsWith("o1") || m.startsWith("o3") || m.contains("/o1") || m.contains("/o3")
  }

  def transformer(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.adjusted(service)((id, caps) => if (isConstrained(id)) constrained else caps)

  // ============================================
  // Temperature constraint tests
  // ============================================

  test("O-series models should reject non-1.0 temperature when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 0.7)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("Temperature")
        result.left.value.message should include("1.0")

  }

  test("O-series models should adjust temperature to 1.0 when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 0.7)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.temperature shouldBe 1.0
  }

  test("O-series models should allow temperature=1.0") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = false)

        result.isRight shouldBe true
        result.toOption.get.temperature shouldBe 1.0

  }

  // ============================================
  // Disallowed parameter tests
  // ============================================

  test("O-series models should reject top_p when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0, topP = 0.9)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("top_p")
  }

  test("O-series models should drop top_p when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0, topP = 0.9)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.topP shouldBe 1.0
  }

  test("O-series models should reject presence_penalty when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0, presencePenalty = 0.5)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("presence_penalty")
  }

  test("O-series models should drop presence_penalty when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0, presencePenalty = 0.5)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.presencePenalty shouldBe 0.0
  }

  test("O-series models should reject frequency_penalty when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0, frequencyPenalty = 0.5)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("frequency_penalty")
  }

  test("O-series models should drop frequency_penalty when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 1.0, frequencyPenalty = 0.5)

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.frequencyPenalty shouldBe 0.0
  }

  // ============================================
  // Multiple violations
  // ============================================

  test("O-series models should report all violations when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(
          temperature = 0.7,
          topP = 0.9,
          presencePenalty = 0.5,
          frequencyPenalty = 0.5
        )

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = false)

        result.isLeft shouldBe true
        val message = result.left.value.message
        message should include("Temperature")
        message should include("top_p")
        message should include("presence_penalty")
        message should include("frequency_penalty")
  }

  test("O-series models should fix all violations when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(
          temperature = 0.7,
          topP = 0.9,
          presencePenalty = 0.5,
          frequencyPenalty = 0.5
        )

        val result = transformer(service).transformOptions("o1", options, dropUnsupported = true)

        result.isRight shouldBe true
        val transformed = result.toOption.get
        transformed.temperature shouldBe 1.0
        transformed.topP shouldBe 1.0
        transformed.presencePenalty shouldBe 0.0
        transformed.frequencyPenalty shouldBe 0.0
  }

  // ============================================
  // System message transformation tests
  // ============================================

  test("O-series models should convert system messages to user messages") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val messages = Seq(
          SystemMessage("You are a helpful assistant."),
          UserMessage("Hello!")
        )

        val result = transformer(service).transformMessages("o1", messages)

        result.length shouldBe 2
        result.head shouldBe a[UserMessage]
        result.head.content should include("[System]:")
        result.head.content should include("You are a helpful assistant.")
  }

  test("Models that support system messages should not transform them") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val messages = Seq(
          SystemMessage("You are a helpful assistant."),
          UserMessage("Hello!")
        )

        val result = transformer(service).transformMessages("gpt-4o", messages)

        result.length shouldBe 2
        result.head shouldBe a[SystemMessage]
        result.head.content shouldBe "You are a helpful assistant."
  }

  // ============================================
  // Streaming support tests
  // ============================================

  test("O-series models should require fake streaming") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        transformer(service).requiresFakeStreaming("o1") shouldBe true
        transformer(service).requiresFakeStreaming("o1-preview") shouldBe true
        transformer(service).requiresFakeStreaming("o1-mini") shouldBe true
  }

  test("Standard models should not require fake streaming") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        transformer(service).requiresFakeStreaming("gpt-4o") shouldBe false
        transformer(service).requiresFakeStreaming("gpt-4-turbo") shouldBe false
  }

  // ============================================
  // O-series model detection tests
  // ============================================

  test("should detect O-series models by name pattern") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        transformer(service).requiresFakeStreaming("o1") shouldBe true
        transformer(service).requiresFakeStreaming("o1-preview") shouldBe true
        transformer(service).requiresFakeStreaming("o1-mini") shouldBe true
        transformer(service).requiresFakeStreaming("o3") shouldBe true
        transformer(service).requiresFakeStreaming("o3-mini") shouldBe true
        transformer(service).requiresFakeStreaming("openai/o1") shouldBe true
  }

  // ============================================
  // Disallowed params query tests
  // ============================================

  test("O-series models should return disallowed params set") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val disallowed = transformer(service).disallowedParams("o1")

        disallowed should contain("top_p")
        disallowed should contain("presence_penalty")
        disallowed should contain("frequency_penalty")
        disallowed should contain("logprobs")
  }

  test("Standard models should return empty disallowed params set") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val disallowed = transformer(service).disallowedParams("gpt-4o")

        disallowed shouldBe empty
  }

  // ============================================
  // Response format (structured output) tests
  // ============================================

  test("should drop Json responseFormat when supportsResponseSchema=false and dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(false))
        val customTransformer = RequestTransformer.withOverrides(Map("test-model" -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)
        val result            = customTransformer.transformOptions("test-model", options, dropUnsupported = true)
        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe None
  }

  test("should keep Json responseFormat when supportsResponseSchema=false and dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(false))
        val customTransformer = RequestTransformer.withOverrides(Map("test-model" -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)

        val result = customTransformer.transformOptions("test-model", options, dropUnsupported = false)

        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe Some(ResponseFormat.Json)
  }

  test(
    "should return error for JsonSchema responseFormat when supportsResponseSchema=false and dropUnsupported=false"
  ) {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(false))
        val customTransformer = RequestTransformer.withOverrides(Map("test-model" -> customCaps), service)
        val schema            = ujson.Obj("type" -> "object")
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))

        val result = customTransformer.transformOptions("test-model", options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("Structured output")
        result.left.value.message should include("JSON schema")
  }

  test("should drop JsonSchema when supportsResponseSchema=false and dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(false))
        val customTransformer = RequestTransformer.withOverrides(Map("test-model" -> customCaps), service)
        val schema            = ujson.Obj("type" -> "object")
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))

        val result = customTransformer.transformOptions("test-model", options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe None
  }

  test("should preserve responseFormat when supportsResponseSchema=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(true))
        val customTransformer = RequestTransformer.withOverrides(Map("test-model" -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)

        val result = customTransformer.transformOptions("test-model", options, dropUnsupported = false)

        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe Some(ResponseFormat.Json)
  }

  test("should preserve responseFormat when supportsResponseSchema=None (unknown)") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = None)
        val customTransformer = RequestTransformer.withOverrides(Map("unknown-model" -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)

        val result = customTransformer.transformOptions("unknown-model", options, dropUnsupported = false)

        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe Some(ResponseFormat.Json)
  }

  // ============================================
  // Custom overrides tests
  // ============================================

  test("custom overrides should take precedence over registry") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps = ModelCapabilities(
          temperatureConstraint = Some((0.0, 0.5)),
          disallowedParams = Some(Set("max_tokens"))
        )

        val customTransformer = RequestTransformer.withOverrides(Map("my-custom-model" -> customCaps), service)
        val options           = CompletionOptions(temperature = 0.7)

        val result = customTransformer.transformOptions("my-custom-model", options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("Temperature")
        result.left.value.message should include("0.5")
  }

  // ============================================
  // TransformationResult convenience method tests
  // ============================================

  test("TransformationResult.transform should transform both options and messages") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = 0.7, topP = 0.9)
        val messages = Seq(
          SystemMessage("Be helpful"),
          UserMessage("Hello")
        )

        val result = TransformationResult.transform(
          "o1",
          options,
          messages,
          transformer(service),
          dropUnsupported = true
        )

        result.isRight shouldBe true
        val tr = result.toOption.get

        tr.options.temperature shouldBe 1.0
        tr.options.topP shouldBe 1.0
        tr.messages.head shouldBe a[UserMessage]
        tr.messages.head.content should include("[System]:")
        tr.requiresFakeStreaming shouldBe true
  }

  test("TransformationResult.transform should fail if dropUnsupported=false and violations exist") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options  = CompletionOptions(temperature = 0.7)
        val messages = Seq(UserMessage("Hello"))

        val result = TransformationResult.transform(
          "o1",
          options,
          messages,
          transformer(service),
          dropUnsupported = false
        )

        result.isLeft shouldBe true
  }

  // ============================================
  // default applies no vendor rules
  // ============================================

  test("default should apply only the registry's capabilities, with no name-based vendor rules") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val withOverride = RequestTransformer.withOverrides(Map("o1" -> ModelCapabilities()), service)
        val options      = CompletionOptions(temperature = 0.7, topP = 0.9)

        withOverride.transformOptions("o1", options, dropUnsupported = false) shouldBe Right(options)
        withOverride.requiresFakeStreaming("o1") shouldBe false
        withOverride.transformMessages("o1", Seq(SystemMessage("s"))) shouldBe Seq(SystemMessage("s"))
  }

  test("adjusted should receive the model id and the capabilities found for it") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        var seen: Option[(String, ModelCapabilities)] = None
        val t = RequestTransformer.adjusted(service) { (id, caps) =>
          seen = Some(id -> caps)
          caps
        }
        t.disallowedParams("some-model")
        seen.map(_._1) shouldBe Some("some-model")
  }
}
