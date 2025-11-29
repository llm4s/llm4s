package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

/**
 * Simple Triage Handoff Example
 *
 * Demonstrates how to use handoffs to route customer queries to specialized agents.
 * A triage agent analyzes the query and hands off to the appropriate specialist.
 */
object SimpleTriageHandoffExample extends App {
  println("=" * 80)
  println("Simple Triage Handoff Example")
  println("=" * 80)

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)

    // Create specialized agents with specific system messages
    supportAgent = new Agent(client)
    salesAgent   = new Agent(client)
    refundAgent  = new Agent(client)

    // Create triage agent
    triageAgent = new Agent(client)

    // Run triage agent with handoff options
    _ = println("\nQuery: 'I want a refund for my order #12345'")
    _ = println("\nTriaging query to appropriate specialist...")

    finalState <- triageAgent.run(
      query = "I want a refund for my order #12345",
      tools = ToolRegistry.empty,
      handoffs = Seq(
        Handoff.to(supportAgent, "General customer support questions"),
        Handoff.to(salesAgent, "Sales and product inquiries"),
        Handoff.to(refundAgent, "Refund and return requests")
      ),
      systemPromptAddition = Some(
        """You are a customer service triage agent.
          |Analyze customer queries and hand off to the appropriate specialist:
          |- Support agent for general questions
          |- Sales agent for product inquiries
          |- Refund agent for refunds and returns
          |
          |IMPORTANT: You MUST hand off to one of the specialist agents.
          |Do not try to answer the question yourself.""".stripMargin
      ),
      debug = true
    )
  } yield finalState

  result match {
    case Right(finalState) =>
      println("\n" + "=" * 80)
      println("✅ Query handled successfully")
      println("=" * 80)
      println(s"Status: ${finalState.status}")
      println(s"\nFinal Response:")
      println(finalState.conversation.messages.last.content)

      if (finalState.logs.exists(_.contains("handoff"))) {
        println(s"\n🔄 Handoff Log:")
        finalState.logs.filter(_.contains("handoff")).foreach(log => println(s"  - $log"))
      }

    case Left(error) =>
      println("\n" + "=" * 80)
      println("❌ Error occurred")
      println("=" * 80)
      println(s"Error: ${error.formatted}")
  }
}
