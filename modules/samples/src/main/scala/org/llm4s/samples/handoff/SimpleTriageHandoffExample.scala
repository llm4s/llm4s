package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Simple Triage Handoff Example
 *
 * Demonstrates how to use handoffs to route customer queries to specialized agents.
 * A triage agent analyzes the query and hands off to the appropriate specialist.
 */
object SimpleTriageHandoffExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 80)
  logger.info("Simple Triage Handoff Example")
  logger.info("=" * 80)

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)

    // Create specialized agents with specific system prompts
    supportAgent = Agent.builder("support", client).withSystemPrompt("You are a customer support specialist.")
    salesAgent   = Agent.builder("sales", client).withSystemPrompt("You are a sales specialist.")
    refundAgent  = Agent.builder("refund", client).withSystemPrompt("You are a refunds and returns specialist.")

    // Create triage agent with handoff options
    triageAgent <- Agent
      .builder("triage", client)
      .withSystemPrompt(
        """You are a customer service triage agent.
          |Analyze customer queries and hand off to the appropriate specialist:
          |- Support agent for general questions
          |- Sales agent for product inquiries
          |- Refund agent for refunds and returns
          |
          |IMPORTANT: You MUST hand off to one of the specialist agents.
          |Do not try to answer the question yourself.""".stripMargin
      )
      .withHandoffs(
        Handoff.to("support", supportAgent, "General customer support questions"),
        Handoff.to("sales", salesAgent, "Sales and product inquiries"),
        Handoff.to("refund", refundAgent, "Refund and return requests")
      )
      .build()

    // Run triage agent
    _ = logger.info("Query: 'I want a refund for my order #12345'")
    _ = logger.info("Triaging query to appropriate specialist...")

    finalState <- triageAgent.run("I want a refund for my order #12345")
  } yield finalState

  result match {
    case Right(finalState) =>
      logger.info("=" * 80)
      logger.info("Query handled successfully")
      logger.info("=" * 80)
      logger.info("Status: {}", finalState.status)
      logger.info("Handled by: {}", finalState.activeAgent.value)
      logger.info("Final Response:")
      logger.info("{}", AgentResults.answerOrStatus(finalState))

    case Left(error) =>
      logger.error("=" * 80)
      logger.error("Error occurred")
      logger.error("=" * 80)
      logger.error("Error: {}", error.formatted)
  }
}
