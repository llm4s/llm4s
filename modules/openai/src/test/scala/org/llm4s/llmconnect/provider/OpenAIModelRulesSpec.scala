package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.model.{ CompletionOptions, SystemMessage, UserMessage }
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService, RequestTransformer, TransformationResult }
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OpenAIModelRulesSpec extends AnyFunSuite with Matchers with EitherValues {

  def transformer(service: ModelRegistryService): RequestTransformer = OpenAIModelRules.transformer(service)

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
  // Disallowed params query tests
  // ============================================

  test("O-series models should return disallowed params set") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(service) =>
        val disallowed = OpenAIModelRules
          .adjust("o1", service.lookup(ModelName("o1")).map(_.capabilities).getOrElse(ModelCapabilities()))
          .disallowedParams
          .getOrElse(Set.empty)

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
        val disallowed = OpenAIModelRules
          .adjust("gpt-4o", service.lookup(ModelName("gpt-4o")).map(_.capabilities).getOrElse(ModelCapabilities()))
          .disallowedParams
          .getOrElse(Set.empty)

        disallowed shouldBe empty
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
  // requiresMaxCompletionTokens tests
  // ============================================

  test("requiresMaxCompletionTokens should correctly identify models requiring max_completion_tokens") {
    org.llm4s.model.ModelRegistryTestSupport.defaultServiceResult() match
      case Left(error) =>
        fail(error.message)
      case Right(_) =>
        val testCases = Seq(
          // O-series models require max_completion_tokens
          ("o1", true),
          ("o1-preview", true),
          ("o1-mini", true),
          ("o3", true),
          ("openai/o1", true),
          ("openai/o3", true),
          // GPT-5 family requires max_completion_tokens
          ("gpt-5", true),
          ("gpt5", true),
          ("openai/gpt-5", true),
          // ...and GPT-6 (#2886)
          ("gpt-5.6-luna", true),
          ("gpt-6-luna", true),
          ("gpt-6.1-terra", true),
          ("openai/gpt-6.2-astra", true),
          ("gpt-6-luna-2026-10-01", true),
          ("openai/gpt-6.1-terra-2026-09-08", true),
          ("ft:gpt-6-luna:my-org::abc123", true),
          ("ft:gpt-6.1-terra-2026-09-08:my-org:custom:abc123", true),
          ("gpt-60", false),
          ("gpt-35-turbo", false),
          // Standard models do NOT require max_completion_tokens
          ("gpt-4o", false),
          ("claude-3", false),
          ("gemini-2.0", false),
          // Case-insensitive detection
          ("O1", true),
          ("GPT-5", true),
          // Unknown models should default to false (safe default)
          ("unknown-model", false)
        )

        testCases.foreach { case (modelId, expected) =>
          withClue(s"Model '$modelId' should return $expected for requiresMaxCompletionTokens: ") {
            OpenAIModelRules.requiresMaxCompletionTokens(modelId) shouldBe expected
          }
        }
  }

  // ============================================
  // Tool calls on Chat Completions (#2886)
  // ============================================

  private lazy val noopTool =
    org.llm4s.toolapi
      .ToolBuilder[String](
        "noop",
        "Does nothing",
        org.llm4s.toolapi.Schema.`object`[Map[String, Any]]("No parameters")
      )
      .withHandler(_ => Right("ok"))
      .buildSafe()
      .fold(e => fail(e.formatted), identity)

  private lazy val withTool = CompletionOptions().withTools(Seq(noopTool))

  test("toolsOnChatCompletions follows OpenAI's model pages, by family, snapshot and prefix") {
    import OpenAIModelRules.ToolCalls
    OpenAIModelRules.toolsOnChatCompletions("gpt-6-luna") shouldBe ToolCalls.OnlyWithoutReasoning
    OpenAIModelRules.toolsOnChatCompletions("openai/gpt-6-luna") shouldBe ToolCalls.OnlyWithoutReasoning
    OpenAIModelRules.toolsOnChatCompletions("gpt-6-luna-2026-10-01") shouldBe ToolCalls.OnlyWithoutReasoning
    OpenAIModelRules.toolsOnChatCompletions("gpt-6.1-terra") shouldBe ToolCalls.ResponsesApiOnly
    OpenAIModelRules.toolsOnChatCompletions("GPT-6.1-TERRA") shouldBe ToolCalls.ResponsesApiOnly
    OpenAIModelRules.toolsOnChatCompletions("openai/gpt-6.1-terra-2026-09-08") shouldBe ToolCalls.ResponsesApiOnly
    // a fine-tune is judged by its base model
    OpenAIModelRules.toolsOnChatCompletions("ft:gpt-6-luna:my-org::abc123") shouldBe ToolCalls.OnlyWithoutReasoning
    OpenAIModelRules.toolsOnChatCompletions("ft:gpt-6.1-terra-2026-09-08:my-org:custom:abc123") shouldBe
      ToolCalls.ResponsesApiOnly
    Seq("gpt-5.6-luna", "gpt-5.6-terra", "gpt-6.2-astra", "gpt-6-lunar", "gpt-6.1-terra-mini", "gpt-4o").foreach { m =>
      withClue(m)(OpenAIModelRules.toolsOnChatCompletions(m) shouldBe ToolCalls.Unrestricted)
    }
  }

  test("a request with tools to gpt-6.1-terra is refused, naming the Responses API") {
    val error = OpenAIModelRules.checkTools("gpt-6.1-terra", withTool).left.value
    error shouldBe a[org.llm4s.error.ValidationError]
    error.message should include("Responses API")
    error.message should include("gpt-6.1-terra")
    OpenAIModelRules.checkTools("gpt-6.1-terra", CompletionOptions()) shouldBe Right(())
    OpenAIModelRules.checkTools("gpt-6-luna", withTool) shouldBe Right(())
  }

  test("a request with tools to gpt-6-luna sends reasoning_effort none and reports what it overrode") {
    import org.llm4s.llmconnect.model.{ ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
    val reason = "gpt-6-luna calls tools on Chat Completions only with reasoning_effort none"

    OpenAIModelRules.forcedToolEffort("gpt-6-luna", withTool) shouldBe Some(
      (
        com.openai.models.ReasoningEffort.NONE,
        List(RequestAdjustment.Adjusted(RequestOption.Reasoning, "model default", "none", reason))
      )
    )
    OpenAIModelRules
      .forcedToolEffort("gpt-6-luna", withTool.withReasoning(ReasoningEffort.High).withThinking(Thinking.Adaptive))
      .map(_._2) shouldBe Some(
      List(
        RequestAdjustment.Adjusted(RequestOption.Reasoning, ReasoningEffort.High.name, "none", reason),
        RequestAdjustment.Adjusted(RequestOption.Thinking, "Adaptive", "Off", reason)
      )
    )
    // thinking = Off already asks for no reasoning: nothing to report
    OpenAIModelRules.forcedToolEffort("gpt-6-luna", withTool.withThinking(Thinking.Off)).map(_._2) shouldBe Some(Nil)
    // no tools, or another model: the usual effort plan applies
    OpenAIModelRules.forcedToolEffort("gpt-6-luna", CompletionOptions()) shouldBe None
    OpenAIModelRules.forcedToolEffort("gpt-5.6-luna", withTool) shouldBe None
  }
}
