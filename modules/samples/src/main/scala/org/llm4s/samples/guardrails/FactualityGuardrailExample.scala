package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Example demonstrating factuality guardrails for RAG applications.
 *
 * This example shows how to use LLM-based factuality guardrails to verify
 * that generated responses are grounded in source documents. This is
 * essential for RAG (Retrieval-Augmented Generation) applications where
 * you want to ensure the model doesn't hallucinate facts.
 *
 * Use cases:
 * - Document Q&A systems
 * - Customer support with knowledge bases
 * - Medical/legal information systems requiring accuracy
 * - Any application where factual accuracy is critical
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object FactualityGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Factuality Guardrail Example (RAG Use Case) ===")

  // Simulated retrieved document content (in real RAG, this comes from vector search)
  val retrievedContext =
    """
    |Scala is a programming language first released in 2004 by Martin Odersky.
    |It runs on the Java Virtual Machine (JVM) and combines object-oriented and
    |functional programming paradigms. Scala supports pattern matching, type
    |inference, and has a powerful type system. The name "Scala" stands for
    |"scalable language". Scala 3, also known as Dotty, was released in 2021
    |and introduced significant improvements to the language including new
    |syntax for enums, union types, and context functions. Major companies
    |using Scala include LinkedIn, Twitter (now X), and Netflix.
    """.stripMargin.trim

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // === Example 1: Standard Factuality Check ===
    _ = logger.info("1. Standard Factuality Check")
    _ = logger.info("-" * 40)
    _ = logger.info("Reference Context:\n{}\n", retrievedContext)

    factualityGuardrail = LLMFactualityGuardrail(
      client,
      referenceContext = retrievedContext,
      threshold = 0.7
    )

    // Query that should be answerable from the context
    query1 = "When was Scala first released and who created it?"

    state1 <- agent.run(
      query = s"Based on the following context, answer this question: $query1\n\nContext: $retrievedContext",
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(factualityGuardrail)
    )

    _ = printResult(state1, "Standard Factuality")

    // === Example 2: Strict Factuality Check ===
    _ = logger.info("2. Strict Factuality Check (Higher Threshold)")
    _ = logger.info("-" * 40)

    strictGuardrail = LLMFactualityGuardrail.strict(client, retrievedContext)

    query2 = "What does the name Scala stand for?"

    state2 <- agent.run(
      query = s"Based on the following context, answer: $query2\n\nContext: $retrievedContext",
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(strictGuardrail)
    )

    _ = printResult(state2, "Strict Factuality")

    // === Example 3: Combined with Safety ===
    _ = logger.info("3. Factuality + Safety Combined")
    _ = logger.info("-" * 40)

    safetyGuardrail = LLMSafetyGuardrail(client)
    combinedGuardrails = Seq(
      safetyGuardrail,    // First ensure response is safe
      factualityGuardrail // Then verify factual accuracy
    )

    query3 = "What are the key features of Scala?"

    state3 <- agent.run(
      query = s"Based on the context, describe: $query3\n\nContext: $retrievedContext",
      tools = ToolRegistry.empty,
      outputGuardrails = combinedGuardrails
    )

    _ = printResult(state3, "Combined Safety + Factuality")

  } yield state3

  result match {
    case Right(_) =>
      logger.info("=" * 50)
      logger.info("✓ All factuality guardrail examples completed successfully!")
      logger.info("Note: In production RAG systems, the context would come from")
      logger.info("vector search over your document embeddings.")

    case Left(error) =>
      logger.error("✗ Example failed with error:")
      logger.error("  {}", error.formatted)
      logger.info("This might indicate the response contained claims not supported")
      logger.info("by the reference context (potential hallucination).")
  }

  def printResult(state: org.llm4s.agent.AgentState, checkName: String): Unit = {
    val response = state.conversation.messages.last.content
    val preview  = if (response.length > 300) response.take(300) + "..." else response

    logger.info("✓ {} Check PASSED (response grounded in context)", checkName)
    logger.info("Response:\n{}", preview)
  }

  logger.info("=" * 50)
}
