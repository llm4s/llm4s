package org.llm4s.model

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.model.{ CompletionOptions, ResponseFormat, SystemMessage, UserMessage }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
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
    RequestTransformer.adjusted(service)((id, caps) => if (isConstrained(id.asString)) constrained else caps)

  // ============================================
  // Temperature constraint tests
  // ============================================

  test("O-series models should reject non-1.0 temperature when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(0.7))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("Temperature")
        result.left.value.message should include("1.0")

  }

  test("O-series models should adjust temperature to 1.0 when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(0.7))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.temperature shouldBe Some(1.0)
  }

  test("O-series models should allow temperature=1.0") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = false)

        result.isRight shouldBe true
        result.toOption.get.temperature shouldBe Some(1.0)

  }

  // ============================================
  // Disallowed parameter tests
  // ============================================

  test("O-series models should reject top_p when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0), topP = Some(0.9))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("top_p")
  }

  test("O-series models should drop top_p when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0), topP = Some(0.9))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.topP shouldBe None
  }

  test("O-series models should reject presence_penalty when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0), presencePenalty = Some(0.5))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("presence_penalty")
  }

  test("O-series models should drop presence_penalty when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0), presencePenalty = Some(0.5))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.presencePenalty shouldBe None
  }

  test("O-series models should reject frequency_penalty when dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0), frequencyPenalty = Some(0.5))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = false)

        result.isLeft shouldBe true
        result.left.value.message should include("frequency_penalty")
  }

  test("O-series models should drop frequency_penalty when dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options = CompletionOptions(temperature = Some(1.0), frequencyPenalty = Some(0.5))

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.frequencyPenalty shouldBe None
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
          temperature = Some(0.7),
          topP = Some(0.9),
          presencePenalty = Some(0.5),
          frequencyPenalty = Some(0.5)
        )

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = false)

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
          temperature = Some(0.7),
          topP = Some(0.9),
          presencePenalty = Some(0.5),
          frequencyPenalty = Some(0.5)
        )

        val result = transformer(service).transformOptions(ModelName("o1"), options, dropUnsupported = true)

        result.isRight shouldBe true
        val transformed = result.toOption.get
        transformed.temperature shouldBe Some(1.0)
        transformed.topP shouldBe None
        transformed.presencePenalty shouldBe None
        transformed.frequencyPenalty shouldBe None
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

        val result = transformer(service).transformMessages(ModelName("o1"), messages)

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

        val result = transformer(service).transformMessages(ModelName("gpt-4o"), messages)

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
        transformer(service).requiresFakeStreaming(ModelName("o1")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("o1-preview")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("o1-mini")) shouldBe true
  }

  test("Standard models should not require fake streaming") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        transformer(service).requiresFakeStreaming(ModelName("gpt-4o")) shouldBe false
        transformer(service).requiresFakeStreaming(ModelName("gpt-4-turbo")) shouldBe false
  }

  // ============================================
  // O-series model detection tests
  // ============================================

  test("should detect O-series models by name pattern") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        transformer(service).requiresFakeStreaming(ModelName("o1")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("o1-preview")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("o1-mini")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("o3")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("o3-mini")) shouldBe true
        transformer(service).requiresFakeStreaming(ModelName("openai/o1")) shouldBe true
  }

  // ============================================
  // Response format (structured output) tests
  // ============================================

  // ============================================
  // Function calling tests
  // ============================================

  private def withPingTool: Either[String, CompletionOptions] =
    ToolBuilder[String]("ping", "Answers pong", Schema.`object`[Map[String, Any]]("No parameters"))
      .withHandler(_ => Right("pong"))
      .buildSafe()
      .left
      .map(_.message)
      .map(tool => CompletionOptions().withTools(Seq(tool)))

  private def noFunctionCalling(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.withOverrides(
      Map(ModelName("test-model") -> ModelCapabilities(supportsFunctionCalling = Some(false))),
      service
    )

  test("should reject tools when the model does not support function calling and dropUnsupported=false") {
    val result = for {
      service <- ModelRegistryTestSupport.defaultServiceResult().left.map(_.message)
      options <- withPingTool
    } yield noFunctionCalling(service).transformOptions(ModelName("test-model"), options, dropUnsupported = false)

    result.value.left.value.message should include("Function calling not supported for test-model")
  }

  test("should drop tools when the model does not support function calling and dropUnsupported=true") {
    val result = for {
      service <- ModelRegistryTestSupport.defaultServiceResult().left.map(_.message)
      options <- withPingTool
    } yield noFunctionCalling(service).transformOptions(ModelName("test-model"), options, dropUnsupported = true)

    result.value.value.tools shouldBe empty
  }

  test("should drop Json responseFormat when supportsResponseSchema=false and dropUnsupported=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(false))
        val customTransformer = RequestTransformer.withOverrides(Map(ModelName("test-model") -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)
        val result = customTransformer.transformOptions(ModelName("test-model"), options, dropUnsupported = true)
        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe None
  }

  test("should keep Json responseFormat when supportsResponseSchema=false and dropUnsupported=false") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(false))
        val customTransformer = RequestTransformer.withOverrides(Map(ModelName("test-model") -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)

        val result = customTransformer.transformOptions(ModelName("test-model"), options, dropUnsupported = false)

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
        val customTransformer = RequestTransformer.withOverrides(Map(ModelName("test-model") -> customCaps), service)
        val schema            = ujson.Obj("type" -> "object")
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))

        val result = customTransformer.transformOptions(ModelName("test-model"), options, dropUnsupported = false)

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
        val customTransformer = RequestTransformer.withOverrides(Map(ModelName("test-model") -> customCaps), service)
        val schema            = ujson.Obj("type" -> "object")
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))

        val result = customTransformer.transformOptions(ModelName("test-model"), options, dropUnsupported = true)

        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe None
  }

  test("should preserve responseFormat when supportsResponseSchema=true") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = Some(true))
        val customTransformer = RequestTransformer.withOverrides(Map(ModelName("test-model") -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)

        val result = customTransformer.transformOptions(ModelName("test-model"), options, dropUnsupported = false)

        result.isRight shouldBe true
        result.toOption.get.responseFormat shouldBe Some(ResponseFormat.Json)
  }

  test("should preserve responseFormat when supportsResponseSchema=None (unknown)") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val customCaps        = ModelCapabilities(supportsResponseSchema = None)
        val customTransformer = RequestTransformer.withOverrides(Map(ModelName("unknown-model") -> customCaps), service)
        val options           = CompletionOptions().withResponseFormat(ResponseFormat.Json)

        val result = customTransformer.transformOptions(ModelName("unknown-model"), options, dropUnsupported = false)

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

        val customTransformer =
          RequestTransformer.withOverrides(Map(ModelName("my-custom-model") -> customCaps), service)
        val options = CompletionOptions(temperature = Some(0.7))

        val result = customTransformer.transformOptions(ModelName("my-custom-model"), options, dropUnsupported = false)

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
        val options = CompletionOptions(temperature = Some(0.7), topP = Some(0.9))
        val messages = Seq(
          SystemMessage("Be helpful"),
          UserMessage("Hello")
        )

        val result = TransformationResult.transform(
          ModelName("o1"),
          options,
          messages,
          transformer(service),
          dropUnsupported = true
        )

        result.isRight shouldBe true
        val tr = result.toOption.get

        tr.options.temperature shouldBe Some(1.0)
        tr.options.topP shouldBe None
        tr.messages.head shouldBe a[UserMessage]
        tr.messages.head.content should include("[System]:")
        tr.requiresFakeStreaming shouldBe true
  }

  test("TransformationResult.transform should fail if dropUnsupported=false and violations exist") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val options  = CompletionOptions(temperature = Some(0.7))
        val messages = Seq(UserMessage("Hello"))

        val result = TransformationResult.transform(
          ModelName("o1"),
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
        val withOverride = RequestTransformer.withOverrides(Map(ModelName("o1") -> ModelCapabilities()), service)
        val options      = CompletionOptions(temperature = Some(0.7), topP = Some(0.9))

        withOverride.transformOptions(ModelName("o1"), options, dropUnsupported = false) shouldBe Right(options)
        withOverride.requiresFakeStreaming(ModelName("o1")) shouldBe false
        withOverride.transformMessages(ModelName("o1"), Seq(SystemMessage("s"))) shouldBe Seq(SystemMessage("s"))
  }

  test("adjusted should receive the model id and the capabilities found for it") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        var seen: Option[(ModelName, ModelCapabilities)] = None
        val t = RequestTransformer.adjusted(service) { (id, caps) =>
          seen = Some(id -> caps)
          caps
        }
        t.requiresFakeStreaming(ModelName("some-model"))
        seen.map(_._1) shouldBe Some(ModelName("some-model"))
  }

  // ============================================
  // Models that take no sampling parameter (#2909)
  // ============================================

  private def noSampling(service: ModelRegistryService, capabilities: ModelCapabilities): RequestTransformer =
    RequestTransformer.adjusted(service)((_, _) => capabilities)

  private val unsampled = List(
    "supports_sampling_params: false" -> ModelCapabilities(supportsSamplingParams = Some(false)),
    "disallowed_params"               -> ModelCapabilities(disallowedParams = Some(Set("temperature", "top_p")))
  )

  test("a temperature and a top_p the model does not take are dropped and reported, whatever their value") {
    import org.llm4s.llmconnect.model.{ RequestAdjustment, RequestOption }
    val service = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    for {
      (label, capabilities) <- unsampled
      temperature           <- List(0.2, 1.0)
    }
      withClue(s"$label, temperature $temperature: ") {
        val options = CompletionOptions(temperature = Some(temperature), topP = Some(0.9), maxTokens = Some(100))
        val (sent, adjustments) =
          noSampling(service, capabilities).adjustOptions(ModelName("some-model"), options).value
        sent.temperature shouldBe None
        sent.topP shouldBe None
        sent.maxTokens shouldBe Some(100)
        adjustments shouldBe Seq(
          RequestAdjustment.Dropped(RequestOption.Temperature, "temperature not supported for some-model"),
          RequestAdjustment.Dropped(RequestOption.TopP, "top_p not supported for some-model")
        )
      }
  }

  test("a temperature the model does not take is an error when unsupported options may not be dropped") {
    val service = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    for ((label, capabilities) <- unsampled)
      withClue(s"$label: ") {
        val result = noSampling(service, capabilities)
          .transformOptions(
            ModelName("some-model"),
            CompletionOptions(temperature = Some(0.2)),
            dropUnsupported = false
          )
        result.left.value.message should include("temperature parameter not supported for some-model")
      }
  }

  test("a model that takes no sampling parameter has nothing dropped when none was set") {
    val service = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    val (sent, adjustments) = noSampling(service, ModelCapabilities(supportsSamplingParams = Some(false)))
      .adjustOptions(ModelName("some-model"), CompletionOptions())
      .value
    sent shouldBe CompletionOptions()
    adjustments shouldBe empty
  }

  test("the bundled catalog's supports_sampling_params is obeyed, and a model without the flag is sent what was set") {
    val service     = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    val transformer = RequestTransformer.default(service)
    val options     = CompletionOptions(temperature = Some(0.2))
    for (
      id <- List("claude-sonnet-5-5", "us.anthropic.claude-sonnet-5-5", "vertex_ai/claude-opus-5-5", "gemini-3.6-flash")
    )
      withClue(s"$id: ")(transformer.adjustOptions(ModelName(id), options).value._1.temperature shouldBe None)
    for (id <- List("claude-sonnet-4-6", "claude-haiku-4-5", "gpt-4o"))
      withClue(s"$id: ")(transformer.adjustOptions(ModelName(id), options).value._1.temperature shouldBe Some(0.2))
  }

  test("UnmappedOptions.sampling drops what the capabilities rule out, and nothing for unknown capabilities") {
    import org.llm4s.llmconnect.model.RequestOption
    val options = CompletionOptions(temperature = Some(0.2), topP = Some(0.9))
    val (kept, report) =
      UnmappedOptions.sampling("m", options, Some(ModelCapabilities(supportsSamplingParams = Some(false))))
    kept.temperature shouldBe None
    kept.topP shouldBe None
    report.map(_.option) shouldBe List(RequestOption.Temperature, RequestOption.TopP)
    UnmappedOptions.sampling("m", options, None) shouldBe (options, Nil)
    UnmappedOptions.sampling("m", options, Some(ModelCapabilities())) shouldBe (options, Nil)
    UnmappedOptions.sampling(
      "m",
      CompletionOptions(),
      Some(ModelCapabilities(supportsSamplingParams = Some(false)))
    ) shouldBe
      (CompletionOptions(), Nil)
  }
}
