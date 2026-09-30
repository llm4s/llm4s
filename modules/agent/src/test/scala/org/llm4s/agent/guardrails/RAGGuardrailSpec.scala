package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.rag._
import org.llm4s.error.{ NetworkError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for RAG guardrails.
 */
class RAGGuardrailSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Mock LLM Clients
  // ==========================================================================

  /**
   * Mock LLM client that returns a configurable grounding response.
   */
  class MockGroundingLLMClient(response: String) extends LLMClient {
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
          content = response,
          model = "test-model",
          message = AssistantMessage(response),
          usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
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
      Left(NetworkError("Mock network error", None, "mock://test"))

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
  // RAGContext Tests
  // ==========================================================================

  "RAGContext" should "combine chunks into single context" in {
    val context = RAGContext(
      query = "What is photosynthesis?",
      retrievedChunks = Seq("Plants convert sunlight.", "Chlorophyll absorbs light.")
    )

    context.combinedContext shouldBe "Plants convert sunlight.\n\nChlorophyll absorbs light."
  }

  it should "provide chunks with sources" in {
    val context = RAGContext.withSources(
      query = "Test",
      chunks = Seq("Chunk 1", "Chunk 2"),
      sources = Seq("doc1.pdf", "doc2.pdf")
    )

    context.chunksWithSources shouldBe Seq(
      ("Chunk 1", Some("doc1.pdf")),
      ("Chunk 2", Some("doc2.pdf"))
    )
  }

  it should "handle missing sources gracefully" in {
    val context = RAGContext(
      query = "Test",
      retrievedChunks = Seq("Chunk 1", "Chunk 2", "Chunk 3")
    )

    context.chunksWithSources should have size 3
    context.chunksWithSources.head shouldBe ("Chunk 1", None)
    context.hasCompleteSources shouldBe false
  }

  it should "detect complete sources" in {
    val context = RAGContext.withSources(
      query = "Test",
      chunks = Seq("Chunk 1", "Chunk 2"),
      sources = Seq("source1", "source2")
    )

    context.hasCompleteSources shouldBe true
  }

  // ==========================================================================
  // GroundingGuardrail Tests
  // ==========================================================================

  "GroundingGuardrail" should "pass when response is well-grounded" in {
    val response =
      """SCORE: 0.95
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: All claims are directly supported by the context.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.8)

    val context = RAGContext(
      query = "What is photosynthesis?",
      retrievedChunks = Seq("Photosynthesis is the process by which plants convert sunlight to energy.")
    )

    val result = guardrail.validateWithContext("Plants convert sunlight to energy.", context)
    result shouldBe Right("Plants convert sunlight to energy.")
  }

  it should "fail when response is not grounded" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Plants grow at night
        |EXPLANATION: The claim about night growth is not supported.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.7)

    val context = RAGContext(
      query = "When do plants grow?",
      retrievedChunks = Seq("Plants grow during the day using sunlight.")
    )

    val result = guardrail.validateWithContext("Plants grow at night.", context)
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.formatted should include("0.30")
  }

  it should "pass when score equals threshold" in {
    val response =
      """SCORE: 0.70
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Marginally grounded.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.7)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)
    result shouldBe Right("Response")
  }

  it should "include ungrounded claims in error message when present" in {
    val response =
      """SCORE: 0.4
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Claim A is false, Claim B is made up
        |EXPLANATION: Multiple unsupported claims found.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.7)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response with false claims", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Claim A is false")
    result.swap.toOption.get.formatted should include("Claim B is made up")
  }

  it should "handle empty chunks by failing in Block mode" in {
    val mockClient = new MockGroundingLLMClient("0.9") // Won't be called
    val guardrail  = new GroundingGuardrail(mockClient, onFail = GuardrailAction.Block)

    val context = RAGContext("Test", Seq.empty)
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("no retrieved chunks")
  }

  it should "pass through with empty chunks in Warn mode" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = new GroundingGuardrail(mockClient, onFail = GuardrailAction.Warn)

    val context = RAGContext("Test", Seq.empty)
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "include query and chunks in LLM request" in {
    val response =
      """SCORE: 0.9
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient)

    val context = RAGContext(
      query = "What color is the sky?",
      retrievedChunks = Seq("The sky appears blue due to Rayleigh scattering.")
    )

    guardrail.validateWithContext("The sky is blue.", context)

    val conversation = mockClient.lastConversation.get
    val userMessage  = conversation.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("What color is the sky?")
    userMessage.content should include("Rayleigh scattering")
    userMessage.content should include("The sky is blue")
  }

  it should "propagate LLM client errors" in {
    val mockClient = new FailingMockLLMClient()
    val guardrail  = GroundingGuardrail(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[NetworkError]
  }

  it should "fall back to score-only parsing" in {
    val mockClient = new MockGroundingLLMClient("0.85")
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.8)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "fail when LLM response is unparseable" in {
    val mockClient = new MockGroundingLLMClient("I cannot evaluate this content")
    val guardrail  = GroundingGuardrail(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Could not parse")
  }

  // ==========================================================================
  // GroundingGuardrail Action Modes
  // ==========================================================================

  it should "allow processing in Warn mode when grounding fails" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Made up fact
        |EXPLANATION: Not grounded.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = new GroundingGuardrail(mockClient, threshold = 0.7, onFail = GuardrailAction.Warn)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "fail in Fix mode (no auto-fix for grounding)" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Made up fact
        |EXPLANATION: Not grounded.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = new GroundingGuardrail(mockClient, threshold = 0.7, onFail = GuardrailAction.Fix)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Cannot auto-fix")
  }

  // ==========================================================================
  // GroundingGuardrail Strict Mode
  // ==========================================================================

  it should "fail in strict mode when any ungrounded claims exist" in {
    val response =
      """SCORE: 0.85
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: Minor unverified detail
        |EXPLANATION: Mostly grounded but one detail can't be verified.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.strict(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    // Strict mode fails on ANY ungrounded claim, even if score is high
    result.isLeft shouldBe true
  }

  it should "pass in strict mode when no ungrounded claims" in {
    val response =
      """SCORE: 0.92
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: All claims verified.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.strict(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  // ==========================================================================
  // GroundingGuardrail Presets
  // ==========================================================================

  "GroundingGuardrail.balanced" should "use 0.7 threshold" in {
    val response =
      """SCORE: 0.72
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.balanced(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  "GroundingGuardrail.lenient" should "use 0.5 threshold" in {
    val response =
      """SCORE: 0.55
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Acceptable.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.lenient(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  "GroundingGuardrail.monitoring" should "warn instead of blocking" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Hallucination
        |EXPLANATION: Poor grounding.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.monitoring(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    // Monitoring mode allows through with warning
    result shouldBe Right("Response")
  }

  // ==========================================================================
  // GroundingGuardrail Standard Validate (without context)
  // ==========================================================================

  "GroundingGuardrail.validate" should "pass through without context" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = GroundingGuardrail(mockClient)

    // Standard validate without context should pass through
    val result = guardrail.validate("Response")
    result shouldBe Right("Response")
  }

  // ==========================================================================
  // RAGGuardrail.all Composition
  // ==========================================================================

  "RAGGuardrail.all" should "pass when all guardrails pass" in {
    val response1 =
      """SCORE: 0.9
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val response2 =
      """SCORE: 0.85
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val client1   = new MockGroundingLLMClient(response1)
    val client2   = new MockGroundingLLMClient(response2)
    val guardrail = RAGGuardrail.all(Seq(GroundingGuardrail(client1), GroundingGuardrail(client2)))

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "fail when any guardrail fails" in {
    val passResponse =
      """SCORE: 0.9
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val failResponse =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Bad claim
        |EXPLANATION: Failed.""".stripMargin

    val passClient = new MockGroundingLLMClient(passResponse)
    val failClient = new MockGroundingLLMClient(failResponse)
    val guardrail  = RAGGuardrail.all(Seq(GroundingGuardrail(passClient), GroundingGuardrail(failClient)))

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
  }

  it should "provide descriptive name" in {
    val client    = new MockGroundingLLMClient("0.9")
    val guardrail = RAGGuardrail.all(Seq(GroundingGuardrail(client), GroundingGuardrail(client)))

    guardrail.name should include("CompositeRAGGuardrail")
    guardrail.name should include("GroundingGuardrail")
  }

  // ==========================================================================
  // GroundingResult Tests
  // ==========================================================================

  "GroundingResult" should "correctly represent grounded state" in {
    val result = GroundingResult(
      score = 0.95,
      isGrounded = true,
      ungroundedClaims = Seq.empty,
      explanation = "All claims supported"
    )

    result.isGrounded shouldBe true
    result.ungroundedClaims shouldBe empty
  }

  it should "correctly represent ungrounded state" in {
    val result = GroundingResult(
      score = 0.3,
      isGrounded = false,
      ungroundedClaims = Seq("Claim 1", "Claim 2"),
      explanation = "Multiple hallucinations"
    )

    result.isGrounded shouldBe false
    result.ungroundedClaims should have size 2
  }

  // ==========================================================================
  // ContextRelevanceGuardrail Tests
  // ==========================================================================

  "ContextRelevanceGuardrail" should "pass when chunks are relevant" in {
    val response =
      """OVERALL_SCORE: 0.85
        |CHUNK_SCORES: 0.9, 0.8
        |EXPLANATION: Both chunks are relevant.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = ContextRelevanceGuardrail(mockClient, threshold = 0.5)

    val context = RAGContext(
      query = "What is machine learning?",
      retrievedChunks = Seq("ML is a subset of AI...", "Neural networks are used in ML...")
    )

    val result = guardrail.validateWithContext("Response", context)
    result shouldBe Right("Response")
  }

  it should "fail when chunks are irrelevant" in {
    val response =
      """OVERALL_SCORE: 0.2
        |CHUNK_SCORES: 0.1, 0.3
        |EXPLANATION: Chunks are not relevant to the query.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = ContextRelevanceGuardrail(mockClient, threshold = 0.5, minRelevantRatio = 0.5)

    val context = RAGContext(
      query = "What is machine learning?",
      retrievedChunks = Seq("Pizza is delicious...", "Weather today is nice...")
    )

    val result = guardrail.validateWithContext("Response", context)
    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("not sufficiently relevant")
  }

  it should "handle empty chunks" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = new ContextRelevanceGuardrail(mockClient, onFail = GuardrailAction.Block)

    val context = RAGContext("Test", Seq.empty)
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("no retrieved chunks")
  }

  it should "pass through without context in standard validate" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = ContextRelevanceGuardrail(mockClient)

    val result = guardrail.validate("Response")
    result shouldBe Right("Response")
  }

  "ContextRelevanceGuardrail.strict" should "require high relevance" in {
    val response =
      """OVERALL_SCORE: 0.65
        |CHUNK_SCORES: 0.7, 0.6
        |EXPLANATION: Somewhat relevant.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = ContextRelevanceGuardrail.strict(mockClient)

    val context = RAGContext("Test", Seq("Chunk1", "Chunk2"))
    val result  = guardrail.validateWithContext("Response", context)

    // Strict requires 0.7 threshold and 0.75 min ratio
    result.isLeft shouldBe true
  }

  "ContextRelevanceGuardrail.monitoring" should "warn instead of blocking" in {
    val response =
      """OVERALL_SCORE: 0.2
        |CHUNK_SCORES: 0.1
        |EXPLANATION: Not relevant.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = ContextRelevanceGuardrail.monitoring(mockClient)

    val context = RAGContext("Test", Seq("Chunk"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  // ==========================================================================
  // ContextRelevanceResult Tests
  // ==========================================================================

  "ContextRelevanceResult" should "identify relevant chunk indices" in {
    val result = ContextRelevanceResult(
      overallScore = 0.6,
      chunkScores = Seq(0.9, 0.2, 0.8, 0.1),
      relevantChunkCount = 2,
      explanation = "Test"
    )

    result.relevantChunkIndices(0.5) shouldBe Seq(0, 2)
    result.irrelevantChunkIndices(0.5) shouldBe Seq(1, 3)
  }

  // ==========================================================================
  // SourceAttributionGuardrail Tests
  // ==========================================================================

  "SourceAttributionGuardrail" should "pass when sources are cited" in {
    val response =
      """HAS_ATTRIBUTIONS: YES
        |ATTRIBUTION_SCORE: 0.9
        |CITED_SOURCES: Document A, Page 5
        |UNCITED_CLAIMS: NONE
        |EXPLANATION: Good attribution.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = SourceAttributionGuardrail(mockClient)

    val context = RAGContext.withSources(
      query = "Test",
      chunks = Seq("Content from doc A"),
      sources = Seq("Document A")
    )

    val result = guardrail.validateWithContext("According to Document A...", context)
    result shouldBe Right("According to Document A...")
  }

  it should "fail when sources are not cited" in {
    val response =
      """HAS_ATTRIBUTIONS: NO
        |ATTRIBUTION_SCORE: 0.1
        |CITED_SOURCES: NONE
        |UNCITED_CLAIMS: Main claim, Secondary claim
        |EXPLANATION: No sources cited.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = SourceAttributionGuardrail(mockClient, minAttributionScore = 0.5)

    val context = RAGContext.withSources(
      query = "Test",
      chunks = Seq("Content"),
      sources = Seq("Source")
    )

    val result = guardrail.validateWithContext("Response without citations", context)
    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("does not properly attribute")
  }

  it should "pass with empty chunks (no sources to attribute)" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = SourceAttributionGuardrail(mockClient)

    val context = RAGContext("Test", Seq.empty)
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "pass when attributions not required" in {
    val mockClient = new MockGroundingLLMClient("0.1")
    val guardrail  = new SourceAttributionGuardrail(mockClient, requireAttributions = false)

    val context = RAGContext("Test", Seq("Chunk"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  "SourceAttributionGuardrail.strict" should "require high-quality attributions" in {
    val response =
      """HAS_ATTRIBUTIONS: YES
        |ATTRIBUTION_SCORE: 0.6
        |CITED_SOURCES: Some source
        |UNCITED_CLAIMS: Missing citation
        |EXPLANATION: Partial attribution.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = SourceAttributionGuardrail.strict(mockClient)

    val context = RAGContext("Test", Seq("Chunk"))
    val result  = guardrail.validateWithContext("Response", context)

    // Strict requires 0.8 score
    result.isLeft shouldBe true
  }

  "SourceAttributionGuardrail.optional" should "allow without citations" in {
    val mockClient = new MockGroundingLLMClient("0.1")
    val guardrail  = SourceAttributionGuardrail.optional(mockClient)

    val context = RAGContext("Test", Seq("Chunk"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  // ==========================================================================
  // SourceAttributionResult Tests
  // ==========================================================================

  "SourceAttributionResult" should "correctly represent citation state" in {
    val result = SourceAttributionResult(
      hasAttributions = true,
      attributionScore = 0.8,
      citedSources = Seq("Doc A", "Doc B"),
      uncitedClaims = Seq.empty,
      explanation = "Good"
    )

    result.hasAttributions shouldBe true
    result.citedSources should have size 2
    result.uncitedClaims shouldBe empty
  }

  // ==========================================================================
  // TopicBoundaryGuardrail Tests
  // ==========================================================================

  "TopicBoundaryGuardrail" should "pass when query is on topic" in {
    val response =
      """IS_ON_TOPIC: YES
        |RELEVANCE_SCORE: 0.9
        |MATCHED_TOPICS: scala programming
        |DETECTED_TOPIC: programming question
        |EXPLANATION: Query is about Scala.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = TopicBoundaryGuardrail(mockClient, Seq("scala programming", "functional programming"))

    val result = guardrail.validate("How do I use pattern matching in Scala?")
    result shouldBe Right("How do I use pattern matching in Scala?")
  }

  it should "fail when query is off topic" in {
    val response =
      """IS_ON_TOPIC: NO
        |RELEVANCE_SCORE: 0.1
        |MATCHED_TOPICS: NONE
        |DETECTED_TOPIC: food and restaurants
        |EXPLANATION: Query is about food, not programming.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = TopicBoundaryGuardrail(mockClient, Seq("scala programming"))

    val result = guardrail.validate("What's the best pizza restaurant?")
    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("outside allowed topic boundaries")
    result.swap.toOption.get.formatted should include("food and restaurants")
  }

  it should "pass with empty allowed topics (no restrictions)" in {
    val mockClient = new MockGroundingLLMClient("0.1")
    val guardrail  = TopicBoundaryGuardrail(mockClient, Seq.empty)

    val result = guardrail.validate("Anything goes")
    result shouldBe Right("Anything goes")
  }

  it should "allow in Warn mode when off-topic" in {
    val response =
      """IS_ON_TOPIC: NO
        |RELEVANCE_SCORE: 0.1
        |MATCHED_TOPICS: NONE
        |DETECTED_TOPIC: off topic
        |EXPLANATION: Not related.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail = TopicBoundaryGuardrail(
      mockClient,
      Seq("programming"),
      threshold = 0.5,
      onFail = GuardrailAction.Warn
    )

    val result = guardrail.validate("Off topic query")
    result shouldBe Right("Off topic query")
  }

  "TopicBoundaryGuardrail.strict" should "require high relevance" in {
    val response =
      """IS_ON_TOPIC: YES
        |RELEVANCE_SCORE: 0.6
        |MATCHED_TOPICS: programming
        |DETECTED_TOPIC: tangentially related
        |EXPLANATION: Loosely related.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = TopicBoundaryGuardrail.strict(mockClient, Seq("programming"))

    val result = guardrail.validate("Test query")
    // Strict requires 0.7 threshold
    result.isLeft shouldBe true
  }

  "TopicBoundaryGuardrail.softwareDevelopment" should "cover common dev topics" in {
    val response =
      """IS_ON_TOPIC: YES
        |RELEVANCE_SCORE: 0.9
        |MATCHED_TOPICS: programming, debugging
        |DETECTED_TOPIC: software development
        |EXPLANATION: Related to coding.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = TopicBoundaryGuardrail.softwareDevelopment(mockClient)

    guardrail.allowedTopics should contain("software development")
    guardrail.allowedTopics should contain("programming")

    val result = guardrail.validate("How do I debug this?")
    result shouldBe Right("How do I debug this?")
  }

  // ==========================================================================
  // TopicBoundaryResult Tests
  // ==========================================================================

  "TopicBoundaryResult" should "correctly represent topic match" in {
    val result = TopicBoundaryResult(
      isOnTopic = true,
      relevanceScore = 0.9,
      matchedTopics = Seq("topic A", "topic B"),
      detectedTopic = "relevant query",
      explanation = "Good match"
    )

    result.isOnTopic shouldBe true
    result.matchedTopics should have size 2
  }

  // ==========================================================================
  // RAGGuardrails Presets Tests
  // ==========================================================================

  "RAGGuardrails.minimal" should "include basic guardrails without LLM" in {
    val config = RAGGuardrails.minimal

    config.inputGuardrails should have size 2
    config.outputGuardrails should have size 1
    config.ragGuardrails shouldBe empty
  }

  "RAGGuardrails.standard" should "include balanced guardrails" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val config     = RAGGuardrails.standard(mockClient)

    config.inputGuardrails should have size 2
    config.outputGuardrails should have size 1
    config.ragGuardrails should have size 2
  }

  "RAGGuardrails.strict" should "include comprehensive guardrails" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val config     = RAGGuardrails.strict(mockClient, Seq("test topic"))

    config.inputGuardrails should have size 3
    config.outputGuardrails should have size 1
    config.ragGuardrails should have size 3
  }

  "RAGGuardrails.monitoring" should "use warn mode" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val config     = RAGGuardrails.monitoring(mockClient)

    config.inputGuardrails should not be empty
    config.ragGuardrails should not be empty
  }

  "RAGGuardrails.custom" should "allow custom combinations" in {
    val config = RAGGuardrails.custom(
      inputGuardrails = Seq(new org.llm4s.agent.guardrails.builtin.LengthCheck(1, 1000)),
      outputGuardrails = Seq.empty,
      ragGuardrails = Seq.empty
    )

    config.inputGuardrails should have size 1
    config.outputGuardrails shouldBe empty
    config.ragGuardrails shouldBe empty
  }

  "RAGGuardrails.allGuardrails" should "flatten all guardrails" in {
    val config = RAGGuardrails.minimal
    val all    = RAGGuardrails.allGuardrails(config)

    all should have size 3 // 2 input + 1 output
  }
}
