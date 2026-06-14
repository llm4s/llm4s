package org.llm4s.error

import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.reliability._
import org.llm4s.testutil.MockLLMClients
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.concurrent.duration._

/**
 * Integration tests verifying that errors originating deep in the stack propagate
 * correctly up through middleware -> LLMClient -> Agent -> caller with the right
 * LLMError subtype and without being swallowed.
 *
 * Covers:
 *  - NetworkError from provider
 *  - RateLimitError through ReliableClient middleware (no retry swallowing)
 *  - ToolCallError from tool execution surfaced in ToolRegistry
 *  - ValidationError (guardrail rejection) surfaced as Left at the caller
 *  - ConfigurationError constructed directly and propagated
 *  - Error-kind round-trip for every LLMError subtype
 */
class ErrorPropagationIntegrationSpec extends AnyFlatSpec with Matchers {

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private def emptyTools: ToolRegistry = ToolRegistry.empty

  // Result type used in tool tests
  case class SimpleResult(value: String)
  implicit val simpleResultRW: ReadWriter[SimpleResult] = macroRW

  // ---------------------------------------------------------------------------
  // 1. NetworkError from provider propagates to agent.run() caller
  // ---------------------------------------------------------------------------

  "NetworkError from a failing provider" should "propagate to agent.run() caller as Left(NetworkError) with original message" in {
    val errorMessage = "Connection refused to mock://test-host"
    val client       = new MockLLMClients.FailingMock(errorMessage)
    val agent        = new Agent(client)

    val result = agent.run(
      query = "Hello",
      tools = emptyTools
    )

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[NetworkError]
    error.message shouldBe errorMessage
  }

  it should "preserve the endpoint field in the NetworkError" in {
    val client = new MockLLMClients.FailingMock("network failure")
    val agent  = new Agent(client)

    val result = agent.run(query = "Hello", tools = emptyTools)

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error match {
      case ne: NetworkError =>
        ne.endpoint should not be empty
      case other =>
        fail(s"Expected NetworkError but got: $other")
    }
  }

  it should "be classified as RecoverableError" in {
    val client = new MockLLMClients.FailingMock("transient failure")
    val agent  = new Agent(client)

    val result = agent.run(query = "Hello", tools = emptyTools)

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  // ---------------------------------------------------------------------------
  // 2. RateLimitError through ReliableClient middleware
  // ---------------------------------------------------------------------------

  "RateLimitError through ReliableClient" should "not be converted to a generic error when reliability is disabled" in {
    val callCount    = new AtomicInteger(0)
    val mockProvider = "test-provider"

    val rateLimitClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        callCount.incrementAndGet()
        Left(RateLimitError(mockProvider))
      }

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)

      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val reliableConfig = ReliabilityConfig.disabled
    val reliableClient = ReliableClient.withProviderName(rateLimitClient, mockProvider, reliableConfig)

    val result = reliableClient.complete(
      Conversation(Seq(UserMessage("hi"))),
      CompletionOptions()
    )

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[RateLimitError]
  }

  it should "propagate the provider field correctly" in {
    val providerName = "my-llm-provider"

    val rateLimitClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(RateLimitError(providerName))

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)

      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val reliableConfig = ReliabilityConfig.disabled
    val reliableClient = ReliableClient.withProviderName(rateLimitClient, providerName, reliableConfig)

    val result = reliableClient.complete(
      Conversation(Seq(UserMessage("hi"))),
      CompletionOptions()
    )

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case rle: RateLimitError => rle.provider shouldBe providerName
      case other               => fail(s"Expected RateLimitError but got: $other")
    }
  }

  "RateLimitError retry behaviour in ReliableClient" should "retry up to maxAttempts then propagate the original error" in {
    val callCount = new AtomicInteger(0)

    val rateLimitClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        callCount.incrementAndGet()
        Left(RateLimitError("retry-provider"))
      }

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)

      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val reliableConfig = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = Duration.Zero),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = None,
      enabled = true
    )
    val reliableClient = ReliableClient.withProviderName(rateLimitClient, "retry-provider", reliableConfig)

    val result = reliableClient.complete(
      Conversation(Seq(UserMessage("hi"))),
      CompletionOptions()
    )

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[RateLimitError]
    // Should have retried exactly maxAttempts times
    callCount.get() shouldBe 3
  }

  // ---------------------------------------------------------------------------
  // 3. ToolCallError from tool execution via ToolRegistry
  // ---------------------------------------------------------------------------

  "ToolRegistry.execute" should "return Left(ToolCallError.UnknownFunction) for unregistered tools" in {
    val registry = ToolRegistry.empty
    val result   = registry.execute(ToolCallRequest("no_such_tool", ujson.Obj()))

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case ToolCallError.UnknownFunction(toolName) =>
        toolName shouldBe "no_such_tool"
      case other => fail(s"Expected UnknownFunction but got: $other")
    }
  }

  it should "return Left(ToolCallError.HandlerError) when tool handler returns Left" in {
    val failingTool = ToolBuilder[Map[String, Any], SimpleResult](
      "handler_error_tool",
      "Always fails with handler error",
      Schema.`object`[Map[String, Any]]("no params")
    ).withHandler(_ => Left("business logic error from tool handler"))
      .buildSafe()
      .getOrElse(fail("Tool build failed"))

    val registry = new ToolRegistry(Seq(failingTool))
    val result   = registry.execute(ToolCallRequest("handler_error_tool", ujson.Obj()))

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case ToolCallError.HandlerError(toolName, errorMsg) =>
        toolName shouldBe "handler_error_tool"
        errorMsg shouldBe "business logic error from tool handler"
      case other => fail(s"Expected HandlerError but got: $other")
    }
  }

  it should "return Left(ToolCallError.ExecutionError) when tool handler throws an exception" in {
    // We create the ToolFunction directly with a handler that throws
    val throwingTool = ToolFunction[Map[String, Any], SimpleResult](
      name = "throwing_tool",
      description = "Always throws a RuntimeException",
      schema = Schema.`object`[Map[String, Any]]("no params"),
      handler = _ => throw new RuntimeException("Tool execution exception inside handler")
    )

    val registry = new ToolRegistry(Seq(throwingTool))
    val result   = registry.execute(ToolCallRequest("throwing_tool", ujson.Obj()))

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case ToolCallError.ExecutionError(toolName, cause) =>
        toolName shouldBe "throwing_tool"
        cause.getMessage should include("Tool execution exception inside handler")
      case other => fail(s"Expected ToolCallError.ExecutionError but got: $other")
    }
  }

  "ToolCallError" should "carry tool name and cause in ExecutionError" in {
    val toolName = "my_special_tool"
    val cause    = new RuntimeException("something went wrong")
    val error    = ToolCallError.ExecutionError(toolName, cause)

    error.toolName shouldBe toolName
    error.cause shouldBe cause
    error.getMessage should include("failed during execution")
    error.getMessage should include("something went wrong")
  }

  it should "carry tool name and message in HandlerError" in {
    val error = ToolCallError.HandlerError("my_tool", "domain validation failed")

    error.toolName shouldBe "my_tool"
    error.error shouldBe "domain validation failed"
    error.getFormattedMessage should include("my_tool")
    error.getFormattedMessage should include("domain validation failed")
  }

  // ---------------------------------------------------------------------------
  // 4. ValidationError (guardrail rejection) surfaced as Left at the caller
  // ---------------------------------------------------------------------------

  "InputGuardrail rejection" should "surface as Left(ValidationError) from agent.run()" in {
    val client = new MockLLMClients.SimpleMock("This should never be reached")
    val agent  = new Agent(client)

    val rejectShortInputGuardrail = new InputGuardrail {
      def name: String = "MinLengthGuardrail"

      def validate(value: String): Result[String] =
        if (value.length >= 50) Right(value)
        else
          Left(
            ValidationError.invalid("input", s"Input must be at least 50 chars; got ${value.length}")
          )
    }

    val result = agent.run(
      query = "Short query",
      tools = emptyTools,
      inputGuardrails = Seq(rejectShortInputGuardrail)
    )

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[ValidationError]
    error.message should include("Input must be at least 50 chars")
  }

  it should "include the rejection reason in the ValidationError" in {
    val client = new MockLLMClients.SimpleMock("never reached")
    val agent  = new Agent(client)

    val rejectAllGuardrail = new InputGuardrail {
      def name: String = "RejectAll"

      def validate(value: String): Result[String] =
        Left(ValidationError("test_field", "always rejected"))
    }

    val result = agent.run(
      query = "any query",
      tools = emptyTools,
      inputGuardrails = Seq(rejectAllGuardrail)
    )

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    // When wrapped by GuardrailApplicator -> CompositeGuardrail, the ValidationError
    // has field "composite" but the original rejection reason is preserved in the message.
    error shouldBe a[ValidationError]
    error.message should include("always rejected")
  }

  it should "be classified as NonRecoverableError" in {
    val client = new MockLLMClients.SimpleMock("never reached")
    val agent  = new Agent(client)

    val rejectGuardrail = new InputGuardrail {
      def name: String = "Reject"

      def validate(value: String): Result[String] =
        Left(ValidationError("field", "rejected"))
    }

    val result = agent.run(
      query = "query",
      tools = emptyTools,
      inputGuardrails = Seq(rejectGuardrail)
    )

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  // ---------------------------------------------------------------------------
  // 5. ConfigurationError constructed directly and verified
  // ---------------------------------------------------------------------------

  "ConfigurationError" should "carry the message and missing keys" in {
    val error = ConfigurationError("Required configuration is missing", List("LLM_MODEL", "OPENAI_API_KEY"))

    error shouldBe a[ConfigurationError]
    error.message should include("Required configuration is missing")
    error.missingKeys should contain("LLM_MODEL")
    error.missingKeys should contain("OPENAI_API_KEY")
  }

  it should "be classified as NonRecoverableError" in {
    val error = ConfigurationError("Missing config")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "include missingKeys in context when present" in {
    val error = ConfigurationError("bad config", List("KEY_ONE", "KEY_TWO"))

    (error.context should contain).key("missingKeys")
    error.context("missingKeys") should include("KEY_ONE")
    error.context("missingKeys") should include("KEY_TWO")
  }

  it should "propagate as Left when returned from a config-like function" in {
    def loadConfig(envMap: Map[String, String]): Result[String] =
      envMap.get("API_KEY") match {
        case Some(key) => Right(key)
        case None =>
          Left(ConfigurationError("API_KEY not set", List("API_KEY")))
      }

    val result = loadConfig(Map.empty)

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[ConfigurationError]
    error.message should include("API_KEY not set")
  }

  // ---------------------------------------------------------------------------
  // 6. Error kind round-trip — each LLMError subtype's fields verified at call site
  // ---------------------------------------------------------------------------

  "NetworkError" should "preserve all fields through construction and extraction" in {
    val cause = new java.net.ConnectException("refused")
    val error = NetworkError("cannot reach host", Some(cause), "https://api.example.com/v1")

    error.message shouldBe "cannot reach host"
    error.cause shouldBe Some(cause)
    error.endpoint shouldBe "https://api.example.com/v1"
    error shouldBe a[RecoverableError]

    error match {
      case NetworkError(msg, c, endpoint) =>
        msg shouldBe "cannot reach host"
        c shouldBe Some(cause)
        endpoint shouldBe "https://api.example.com/v1"
      case _ => fail("NetworkError unapply failed")
    }
  }

  "RateLimitError" should "preserve all fields through construction and extraction" in {
    val error = RateLimitError("anthropic", 30L)

    error.provider shouldBe "anthropic"
    error.retryAfter shouldBe Some(30L)
    error.message should include("Rate limited by anthropic")
    error shouldBe a[RecoverableError]

    error match {
      case RateLimitError(msg, retryAfter, provider) =>
        msg should include("Rate limited")
        retryAfter shouldBe Some(30L)
        provider shouldBe "anthropic"
      case _ => fail("RateLimitError unapply failed")
    }
  }

  "ValidationError" should "preserve all fields through construction and extraction" in {
    val error = ValidationError("email_address", List("format invalid", "domain unreachable"))

    error.field shouldBe "email_address"
    error.violations should contain("format invalid")
    error.violations should contain("domain unreachable")
    error shouldBe a[NonRecoverableError]

    error match {
      case ValidationError(msg, field, violations) =>
        field shouldBe "email_address"
        violations should have size 2
        msg should include("email_address")
      case _ => fail("ValidationError unapply failed")
    }
  }

  "ConfigurationError" should "preserve all fields through construction and extraction" in {
    val error = ConfigurationError("provider not found", List("LLM_PROVIDER"))

    error.missingKeys shouldBe List("LLM_PROVIDER")
    error shouldBe a[NonRecoverableError]

    error match {
      case ConfigurationError(msg, missingKeys) =>
        msg should include("provider not found")
        missingKeys should contain("LLM_PROVIDER")
      case _ => fail("ConfigurationError unapply failed")
    }
  }

  "AuthenticationError" should "preserve all fields through construction and extraction" in {
    val error = AuthenticationError("openai", "invalid API key", "401")

    error.provider shouldBe "openai"
    error.code shouldBe Some("401")
    error.message should include("Authentication failed for openai")
    error shouldBe a[NonRecoverableError]

    error match {
      case AuthenticationError(msg, provider, code) =>
        provider shouldBe "openai"
        code shouldBe Some("401")
        msg should include("Authentication failed")
      case _ => fail("AuthenticationError unapply failed")
    }
  }

  "ServiceError" should "preserve all fields through construction and extraction" in {
    val error = ServiceError(503, "openai", "Service unavailable")

    error.httpStatus shouldBe 503
    error.provider shouldBe "openai"
    error.code shouldBe Some("503")
    error shouldBe a[RecoverableError]

    error match {
      case ServiceError(msg, status, provider, requestId) =>
        status shouldBe 503
        provider shouldBe "openai"
        requestId shouldBe None
        msg should include("Service error from openai")
      case _ => fail("ServiceError unapply failed")
    }
  }

  "ProcessingError" should "preserve all fields through construction and extraction" in {
    val cause = new IllegalArgumentException("malformed input")
    val error = ProcessingError("image-resize", "Width must be positive", Some(cause))

    error.operation shouldBe "image-resize"
    error.cause shouldBe Some(cause)
    error.message should include("Processing failed during image-resize")
    error shouldBe a[NonRecoverableError]

    error match {
      case ProcessingError(msg, operation, c) =>
        operation shouldBe "image-resize"
        c shouldBe Some(cause)
        msg should include("image-resize")
      case _ => fail("ProcessingError unapply failed")
    }
  }

  "UnknownError" should "preserve all fields through construction and extraction" in {
    val cause = new RuntimeException("unexpected")
    val error = UnknownError("Something went wrong", cause)

    error.message shouldBe "Something went wrong"
    error.cause shouldBe cause
    error shouldBe a[NonRecoverableError]

    error match {
      case UnknownError(msg, c) =>
        msg shouldBe "Something went wrong"
        c shouldBe cause
      case _ => fail("UnknownError unapply failed")
    }
  }

  "TimeoutError" should "preserve all fields" in {
    val error = TimeoutError("Request timed out", 30.seconds, "llm-completion")

    error.message shouldBe "Request timed out"
    error.timeoutDuration shouldBe 30.seconds
    error.operation shouldBe "llm-completion"
    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  "ExecutionError" should "preserve all fields" in {
    val error = ExecutionError("Script failed", "bash-command", Some(1))

    error.message shouldBe "Script failed"
    error.operation shouldBe "bash-command"
    error.exitCode shouldBe Some(1)
    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  // ---------------------------------------------------------------------------
  // 7. LLMError recoverability classification covers all subtypes
  // ---------------------------------------------------------------------------

  "LLMError.isRecoverable" should "return true for all RecoverableError subtypes" in {
    val recoverableErrors: Seq[LLMError] = Seq(
      NetworkError("net fail", None, "endpoint"),
      RateLimitError("provider"),
      ServiceError(503, "provider", "unavailable"),
      TimeoutError("timed out", 10.seconds, "op"),
      ExecutionError("exec failed", "op"),
      APIError("provider", "api error"),
      SystemError("sys error")
    )

    recoverableErrors.foreach { error =>
      withClue(s"${error.getClass.getSimpleName} should be recoverable") {
        LLMError.isRecoverable(error) shouldBe true
        error shouldBe a[RecoverableError]
      }
    }
  }

  it should "return false for all NonRecoverableError subtypes" in {
    val nonRecoverableErrors: Seq[LLMError] = Seq(
      ValidationError("field", "bad"),
      ConfigurationError("missing key"),
      AuthenticationError("provider", "bad key"),
      ProcessingError("op", "proc failed"),
      UnknownError("unknown", new RuntimeException("ex"))
    )

    nonRecoverableErrors.foreach { error =>
      withClue(s"${error.getClass.getSimpleName} should NOT be recoverable") {
        LLMError.isRecoverable(error) shouldBe false
        error shouldBe a[NonRecoverableError]
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 8. Error formatting — formatted field verified at call site
  // ---------------------------------------------------------------------------

  "LLMError.formatted" should "include the error class name for all subtypes" in {
    val errors: Seq[LLMError] = Seq(
      NetworkError("net error", None, "ep"),
      RateLimitError("prov"),
      ValidationError("field", "bad"),
      ConfigurationError("missing"),
      AuthenticationError("prov", "bad key"),
      ServiceError(502, "prov", "gateway"),
      ProcessingError("op", "failed"),
      UnknownError("unknown", new RuntimeException("ex")),
      TimeoutError("timed out", 5.seconds, "op"),
      ExecutionError("exec failed", "op")
    )

    errors.foreach { error =>
      val formatted = error.formatted
      withClue(s"formatted() for ${error.getClass.getSimpleName}") {
        formatted should not be empty
        formatted should include(error.getClass.getSimpleName)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 9. End-to-end: errors propagate through Agent to caller unchanged
  // ---------------------------------------------------------------------------

  "Agent.run" should "propagate NetworkError from LLM client to caller without wrapping" in {
    val originalError = NetworkError("original network failure", None, "http://llm.service/api")

    val errorClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(originalError)

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)

      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val agent  = new Agent(errorClient)
    val result = agent.run(query = "test", tools = emptyTools)

    result.isLeft shouldBe true
    val returnedError = result.swap.toOption.get
    returnedError shouldBe a[NetworkError]
    returnedError.message shouldBe "original network failure"
  }

  "Agent.run" should "propagate RateLimitError from LLM client to caller" in {
    val originalError = RateLimitError("anthropic", 60L)

    val rateLimitClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(originalError)

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)

      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val agent  = new Agent(rateLimitClient)
    val result = agent.run(query = "test", tools = emptyTools)

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case rle: RateLimitError =>
        rle.provider shouldBe "anthropic"
        rle.retryAfter shouldBe Some(60L)
      case other => fail(s"Expected RateLimitError but got: ${other.getClass.getSimpleName}")
    }
  }

  "Agent.run" should "propagate AuthenticationError from LLM client to caller" in {
    val originalError = AuthenticationError("openai", "invalid key", "401")

    val authErrorClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(originalError)

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)

      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    val agent  = new Agent(authErrorClient)
    val result = agent.run(query = "test", tools = emptyTools)

    result.isLeft shouldBe true
    result.swap.toOption.get match {
      case ae: AuthenticationError =>
        ae.provider shouldBe "openai"
        ae.code shouldBe Some("401")
      case other => fail(s"Expected AuthenticationError but got: ${other.getClass.getSimpleName}")
    }
  }
}
