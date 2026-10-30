package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.agent.guardrails.builtin.{ InjectionSensitivity, PIIDetector, PIIMasker, PromptInjectionDetector }
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * Pins the composition of the RAG guardrail presets that RAGGuardrailSpec does not cover:
 * standardWithTopics, customerSupport, softwareDocumentation, research and financial.
 *
 * None of these tests call the LLM; they only inspect how each preset is assembled.
 */
class RAGGuardrailsPresetsSpec extends AnyFlatSpec with Matchers {

  /** Stub LLM client; the presets only store it, so it is never invoked here. */
  class MockGroundingLLMClient(response: String) extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Right(
        Completion(
          id = "test-id",
          created = Instant.now(),
          content = response,
          model = "test-model",
          message = AssistantMessage(response),
          usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
        )
      )

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private val client = new MockGroundingLLMClient("SCORE: 1.0\nGROUNDED: YES")

  private def classes(guardrails: Seq[AnyRef]): Seq[Class[_]] = guardrails.map(_.getClass)

  private def topicBoundary(config: RAGGuardrails.GuardrailConfig): TopicBoundaryGuardrail =
    config.inputGuardrails.collect { case t: TopicBoundaryGuardrail => t } match {
      case Seq(t) => t
      case other  => fail(s"expected exactly one TopicBoundaryGuardrail, got ${other.size}")
    }

  private def grounding(config: RAGGuardrails.GuardrailConfig): GroundingGuardrail =
    config.ragGuardrails.collect { case g: GroundingGuardrail => g } match {
      case Seq(g) => g
      case other  => fail(s"expected exactly one GroundingGuardrail, got ${other.size}")
    }

  private def attribution(config: RAGGuardrails.GuardrailConfig): SourceAttributionGuardrail =
    config.ragGuardrails.collect { case s: SourceAttributionGuardrail => s } match {
      case Seq(s) => s
      case other  => fail(s"expected exactly one SourceAttributionGuardrail, got ${other.size}")
    }

  private def injection(config: RAGGuardrails.GuardrailConfig): PromptInjectionDetector =
    config.inputGuardrails.collect { case p: PromptInjectionDetector => p } match {
      case Seq(p) => p
      case other  => fail(s"expected exactly one PromptInjectionDetector, got ${other.size}")
    }

  private def contextRelevance(config: RAGGuardrails.GuardrailConfig): ContextRelevanceGuardrail =
    config.ragGuardrails.collect { case c: ContextRelevanceGuardrail => c } match {
      case Seq(c) => c
      case other  => fail(s"expected exactly one ContextRelevanceGuardrail, got ${other.size}")
    }

  private def assertAllGuardrailsSize(config: RAGGuardrails.GuardrailConfig): Unit =
    RAGGuardrails.allGuardrails(config) should have size
      (config.inputGuardrails.size + config.outputGuardrails.size + config.ragGuardrails.size).toLong

  // ==========================================================================
  // standardWithTopics
  // ==========================================================================

  "RAGGuardrails.standardWithTopics" should "compose PII, injection and topic input guardrails in order" in {
    val config = RAGGuardrails.standardWithTopics(client, Seq("scala", "functional programming"))

    classes(config.inputGuardrails) shouldBe Seq(
      classOf[PIIDetector],
      classOf[PromptInjectionDetector],
      classOf[TopicBoundaryGuardrail]
    )
    classes(config.outputGuardrails) shouldBe Seq(classOf[PIIMasker])
    classes(config.ragGuardrails) shouldBe Seq(classOf[GroundingGuardrail], classOf[ContextRelevanceGuardrail])
    assertAllGuardrailsSize(config)
  }

  it should "use balanced settings and pass the allowed topics through unchanged" in {
    val topics = Seq("scala", "functional programming")
    val config = RAGGuardrails.standardWithTopics(client, topics)

    injection(config).sensitivity shouldBe InjectionSensitivity.Medium

    val topic = topicBoundary(config)
    topic.allowedTopics shouldBe topics
    topic.threshold shouldBe 0.5
    topic.onFail shouldBe GuardrailAction.Block

    val ground = grounding(config)
    ground.threshold shouldBe 0.7
    ground.strictMode shouldBe false
    ground.onFail shouldBe GuardrailAction.Block

    contextRelevance(config).threshold shouldBe 0.5
  }

  // ==========================================================================
  // customerSupport
  // ==========================================================================

  "RAGGuardrails.customerSupport" should "compose the same guardrail types as standardWithTopics" in {
    val config = RAGGuardrails.customerSupport(client, Seq("widgets"))

    classes(config.inputGuardrails) shouldBe Seq(
      classOf[PIIDetector],
      classOf[PromptInjectionDetector],
      classOf[TopicBoundaryGuardrail]
    )
    classes(config.outputGuardrails) shouldBe Seq(classOf[PIIMasker])
    classes(config.ragGuardrails) shouldBe Seq(classOf[GroundingGuardrail], classOf[ContextRelevanceGuardrail])
    assertAllGuardrailsSize(config)
  }

  it should "extend the product topics with the six support topics" in {
    val productTopics = Seq("widgets", "gadgets")
    val config        = RAGGuardrails.customerSupport(client, productTopics)

    val topic = topicBoundary(config)
    topic.allowedTopics shouldBe productTopics ++ Seq(
      "product questions",
      "technical support",
      "troubleshooting",
      "account issues",
      "billing questions",
      "feature requests"
    )
    topic.threshold shouldBe 0.5
    topic.onFail shouldBe GuardrailAction.Block

    grounding(config).threshold shouldBe 0.7
    grounding(config).onFail shouldBe GuardrailAction.Block
  }

  // ==========================================================================
  // softwareDocumentation
  // ==========================================================================

  "RAGGuardrails.softwareDocumentation" should "have no output guardrails and no PII detector" in {
    val config = RAGGuardrails.softwareDocumentation(client)

    classes(config.inputGuardrails) shouldBe Seq(classOf[PromptInjectionDetector], classOf[TopicBoundaryGuardrail])
    config.outputGuardrails shouldBe empty
    classes(config.ragGuardrails) shouldBe Seq(classOf[GroundingGuardrail], classOf[SourceAttributionGuardrail])
    assertAllGuardrailsSize(config)
  }

  it should "use balanced grounding and attribution with software development topics" in {
    val config = RAGGuardrails.softwareDocumentation(client)

    injection(config).sensitivity shouldBe InjectionSensitivity.Medium

    val topic = topicBoundary(config)
    (topic.allowedTopics should contain).allOf("software development", "programming", "debugging")
    topic.threshold shouldBe 0.5
    topic.onFail shouldBe GuardrailAction.Block

    grounding(config).threshold shouldBe 0.7
    grounding(config).strictMode shouldBe false

    val attr = attribution(config)
    attr.requireAttributions shouldBe true
    attr.minAttributionScore shouldBe 0.5
    attr.onFail shouldBe GuardrailAction.Block
  }

  // ==========================================================================
  // research
  // ==========================================================================

  "RAGGuardrails.research" should "only guard input against prompt injection" in {
    val config = RAGGuardrails.research(client)

    classes(config.inputGuardrails) shouldBe Seq(classOf[PromptInjectionDetector])
    classes(config.outputGuardrails) shouldBe Seq(classOf[PIIMasker])
    classes(config.ragGuardrails) shouldBe Seq(
      classOf[GroundingGuardrail],
      classOf[ContextRelevanceGuardrail],
      classOf[SourceAttributionGuardrail]
    )
    assertAllGuardrailsSize(config)
  }

  it should "use strict grounding and strict source attribution" in {
    val config = RAGGuardrails.research(client)

    injection(config).sensitivity shouldBe InjectionSensitivity.Medium

    val ground = grounding(config)
    ground.threshold shouldBe 0.9
    ground.strictMode shouldBe true
    ground.onFail shouldBe GuardrailAction.Block

    contextRelevance(config).threshold shouldBe 0.5

    val attr = attribution(config)
    attr.requireAttributions shouldBe true
    attr.minAttributionScore shouldBe 0.8
    attr.onFail shouldBe GuardrailAction.Block
  }

  // ==========================================================================
  // financial
  // ==========================================================================

  "RAGGuardrails.financial" should "compose financial PII, strict injection and topic guardrails" in {
    val config = RAGGuardrails.financial(client, Seq("investing"))

    classes(config.inputGuardrails) shouldBe Seq(
      classOf[PIIDetector],
      classOf[PromptInjectionDetector],
      classOf[TopicBoundaryGuardrail]
    )
    classes(config.outputGuardrails) shouldBe Seq(classOf[PIIMasker])
    classes(config.ragGuardrails) shouldBe Seq(classOf[GroundingGuardrail], classOf[SourceAttributionGuardrail])
    assertAllGuardrailsSize(config)
  }

  it should "use strict settings and financial PII types" in {
    val topics = Seq("investing", "banking")
    val config = RAGGuardrails.financial(client, topics)

    val financialTypes = Seq(PIIType.CreditCard, PIIType.SSN, PIIType.BankAccount)
    config.inputGuardrails.collect { case d: PIIDetector => d.piiTypes } shouldBe Seq(financialTypes)
    config.outputGuardrails.collect { case m: PIIMasker => m.piiTypes } shouldBe Seq(financialTypes)

    injection(config).sensitivity shouldBe InjectionSensitivity.High

    val topic = topicBoundary(config)
    topic.allowedTopics shouldBe topics
    topic.threshold shouldBe 0.7
    topic.onFail shouldBe GuardrailAction.Block

    val ground = grounding(config)
    ground.threshold shouldBe 0.9
    ground.strictMode shouldBe true
    ground.onFail shouldBe GuardrailAction.Block

    val attr = attribution(config)
    attr.requireAttributions shouldBe true
    attr.minAttributionScore shouldBe 0.8
    attr.onFail shouldBe GuardrailAction.Block
  }
}
