package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin._
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for LLM-as-Judge guardrails.
 *
 * These tests use a mock LLM client to verify the guardrail logic
 * without making actual API calls.
 */
class LLMGuardrailSpec extends AnyFlatSpec with Matchers {

  /**
   * Mock LLM client that returns a configurable score.
   */
  class MockLLMClient(scoreToReturn: String) extends LLMClient {
    var lastConversation: Option[Conversation] = None

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      lastConversation = Some(conversation)
      Right(
        Completion(
          id = "test-id",
          created = System.currentTimeMillis(),
          content = scoreToReturn,
          model = "test-model",
          message = AssistantMessage(scoreToReturn),
          usage = Some(TokenUsage(promptTokens = 10, completionTokens = 1, totalTokens = 11))
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /**
   * Mock LLM client that returns an error.
   */
  class FailingMockLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Left(org.llm4s.error.NetworkError("Mock network error", None, "mock://test"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // ==========================================================================
  // LLMGuardrail Base Tests
  // ==========================================================================

  "LLMGuardrail" should "pass when score is above threshold" in {
    val mockClient = new MockLLMClient("0.85")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result shouldBe Right("Test content")
  }

  it should "fail when score is below threshold" in {
    val mockClient = new MockLLMClient("0.4")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.formatted should include("0.40")
    result.swap.toOption.get.formatted should include("0.70")
  }

  it should "pass when score equals threshold" in {
    val mockClient = new MockLLMClient("0.70")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result shouldBe Right("Test content")
  }

  it should "parse scores with extra whitespace" in {
    val mockClient = new MockLLMClient("  0.9  \n")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result shouldBe Right("Test content")
  }

  it should "clamp scores above 1.0 to 1.0" in {
    val mockClient = new MockLLMClient("1.5")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.99,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result shouldBe Right("Test content")
  }

  it should "handle negative scores by stripping non-numeric chars" in {
    // Note: The current implementation strips non-numeric chars like "-"
    // So "-0.5" becomes "0.5" which is clamped to valid range
    val mockClient = new MockLLMClient("-0.5")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.4,
      guardrailName = "TestGuardrail"
    )

    // "-0.5" parses as "0.5" after stripping "-"
    val result = guardrail.validate("Test content")
    result shouldBe Right("Test content") // 0.5 >= 0.4
  }

  it should "fail gracefully when LLM returns unparseable response" in {
    val mockClient = new MockLLMClient("I cannot provide a score for this.")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Could not parse")
  }

  it should "propagate LLM client errors" in {
    val mockClient = new FailingMockLLMClient()
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Rate quality",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    val result = guardrail.validate("Test content")
    result.isLeft shouldBe true
  }

  it should "include content and prompt in LLM request" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail = LLMGuardrail(
      client = mockClient,
      prompt = "Check for professionalism",
      passThreshold = 0.7,
      guardrailName = "TestGuardrail"
    )

    guardrail.validate("My content here")

    val conversation = mockClient.lastConversation.get
    val userMessage  = conversation.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("Check for professionalism")
    userMessage.content should include("My content here")
  }

  // ==========================================================================
  // LLMToneGuardrail Tests
  // ==========================================================================

  "LLMToneGuardrail" should "pass for matching tone" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail  = LLMToneGuardrail(mockClient, Set("professional", "formal"))

    val result = guardrail.validate("Professional content")
    result shouldBe Right("Professional content")
  }

  it should "fail for mismatching tone" in {
    val mockClient = new MockLLMClient("0.3")
    val guardrail  = LLMToneGuardrail(mockClient, Set("professional"))

    val result = guardrail.validate("Casual content")
    result.isLeft shouldBe true
  }

  it should "include allowed tones in evaluation prompt" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail  = LLMToneGuardrail(mockClient, Set("friendly", "warm"))

    guardrail.validate("Test")

    val userMessage = mockClient.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("friendly")
    userMessage.content should include("warm")
  }

  "LLMToneGuardrail.professional" should "create professional tone guardrail" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail  = LLMToneGuardrail.professional(mockClient)

    guardrail.validate("Test")

    val userMessage = mockClient.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("professional")
  }

  // ==========================================================================
  // LLMFactualityGuardrail Tests
  // ==========================================================================

  "LLMFactualityGuardrail" should "pass for factually accurate content" in {
    val mockClient = new MockLLMClient("0.95")
    val guardrail = LLMFactualityGuardrail(
      mockClient,
      "Paris is the capital of France.",
      threshold = 0.8
    )

    val result = guardrail.validate("Paris is indeed the capital of France.")
    result shouldBe Right("Paris is indeed the capital of France.")
  }

  it should "fail for factually inaccurate content" in {
    val mockClient = new MockLLMClient("0.2")
    val guardrail = LLMFactualityGuardrail(
      mockClient,
      "Paris is the capital of France.",
      threshold = 0.8
    )

    val result = guardrail.validate("Berlin is the capital of France.")
    result.isLeft shouldBe true
  }

  it should "include reference context in evaluation prompt" in {
    val mockClient       = new MockLLMClient("0.9")
    val referenceContext = "The sky is blue during clear days."
    val guardrail        = LLMFactualityGuardrail(mockClient, referenceContext)

    guardrail.validate("Test")

    val userMessage = mockClient.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("The sky is blue during clear days.")
  }

  "LLMFactualityGuardrail.strict" should "use higher threshold" in {
    val mockClient = new MockLLMClient("0.85")
    val guardrail  = LLMFactualityGuardrail.strict(mockClient, "Reference")

    // 0.85 < 0.9 (strict threshold)
    val result = guardrail.validate("Test")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // LLMSafetyGuardrail Tests
  // ==========================================================================

  "LLMSafetyGuardrail" should "pass for safe content" in {
    val mockClient = new MockLLMClient("0.95")
    val guardrail  = LLMSafetyGuardrail(mockClient)

    val result = guardrail.validate("Safe and appropriate content")
    result shouldBe Right("Safe and appropriate content")
  }

  it should "fail for unsafe content" in {
    val mockClient = new MockLLMClient("0.2")
    val guardrail  = LLMSafetyGuardrail(mockClient)

    val result = guardrail.validate("Potentially unsafe content")
    result.isLeft shouldBe true
  }

  it should "use higher default threshold than other guardrails" in {
    val mockClient = new MockLLMClient("0.75")
    val guardrail  = LLMSafetyGuardrail(mockClient) // Default threshold is 0.8

    val result = guardrail.validate("Content")
    result.isLeft shouldBe true // 0.75 < 0.8
  }

  "LLMSafetyGuardrail.strict" should "use even higher threshold" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail  = LLMSafetyGuardrail.strict(mockClient) // Threshold is 0.95

    val result = guardrail.validate("Content")
    result.isLeft shouldBe true // 0.9 < 0.95
  }

  "LLMSafetyGuardrail.withCustomCriteria" should "include custom criteria" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail = LLMSafetyGuardrail.withCustomCriteria(
      mockClient,
      "Must not discuss competitors"
    )

    guardrail.validate("Test")

    val userMessage = mockClient.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("Must not discuss competitors")
  }

  // ==========================================================================
  // LLMQualityGuardrail Tests
  // ==========================================================================

  "LLMQualityGuardrail" should "pass for high quality responses" in {
    val mockClient = new MockLLMClient("0.9")
    val guardrail  = LLMQualityGuardrail(mockClient, "What is Scala?")

    val result = guardrail.validate("Scala is a programming language...")
    result shouldBe Right("Scala is a programming language...")
  }

  it should "fail for low quality responses" in {
    val mockClient = new MockLLMClient("0.3")
    val guardrail  = LLMQualityGuardrail(mockClient, "What is Scala?")

    val result = guardrail.validate("IDK")
    result.isLeft shouldBe true
  }

  it should "include original query in evaluation prompt" in {
    val mockClient    = new MockLLMClient("0.9")
    val originalQuery = "Explain machine learning"
    val guardrail     = LLMQualityGuardrail(mockClient, originalQuery)

    guardrail.validate("Test response")

    val userMessage = mockClient.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("Explain machine learning")
  }

  // ==========================================================================
  // Composition Tests
  // ==========================================================================

  "LLM guardrails" should "compose with function-based guardrails" in {
    val mockClient   = new MockLLMClient("0.9")
    val llmGuardrail = LLMSafetyGuardrail(mockClient)
    val lengthCheck  = new LengthCheck(1, 1000)

    val composite = CompositeGuardrail.all(Seq(lengthCheck, llmGuardrail))

    val result = composite.validate("Safe content")
    result shouldBe Right("Safe content")
  }

  it should "fail composite if any guardrail fails" in {
    val mockClient   = new MockLLMClient("0.2") // LLM says unsafe
    val llmGuardrail = LLMSafetyGuardrail(mockClient)
    val lengthCheck  = new LengthCheck(1, 1000) // Length is fine

    val composite = CompositeGuardrail.all(Seq(lengthCheck, llmGuardrail))

    val result = composite.validate("Content")
    result.isLeft shouldBe true
  }

  it should "chain with andThen" in {
    val mockClient1 = new MockLLMClient("0.9")
    val mockClient2 = new MockLLMClient("0.95")

    val safetyGuardrail = LLMSafetyGuardrail(mockClient1)
    val toneGuardrail   = LLMToneGuardrail.professional(mockClient2)

    val composed = safetyGuardrail.andThen(toneGuardrail)

    val result = composed.validate("Professional and safe content")
    result shouldBe Right("Professional and safe content")
  }
}
