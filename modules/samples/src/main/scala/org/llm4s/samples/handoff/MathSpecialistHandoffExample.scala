package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

/**
 * Math Specialist Handoff Example
 *
 * Demonstrates how a general agent can hand off mathematical queries
 * to a specialist agent with expertise in mathematics.
 */
object MathSpecialistHandoffExample extends App {
  println("=" * 80)
  println("Math Specialist Handoff Example")
  println("=" * 80)

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)

    // General agent
    generalAgent = new Agent(client)

    // Math specialist with specialized system message
    mathAgent = new Agent(client)

    // Run general agent with math handoff
    _ = println("\nQuery: 'What is the integral of 2x + 5 from 0 to 10?'")
    _ = println("\nGeneral agent analyzing query...")

    finalState <- generalAgent.run(
      query = "What is the integral of 2x + 5 from 0 to 10?",
      tools = ToolRegistry.empty,
      handoffs = Seq(
        Handoff.to(
          mathAgent,
          "Mathematical questions requiring calculus or advanced math"
        )
      ),
      systemPromptAddition = Some(
        """You are a general assistant.
          |For mathematical questions involving calculus, algebra, or advanced math,
          |you MUST hand off to the math specialist.
          |Do not attempt to solve advanced math problems yourself.""".stripMargin
      ),
      debug = false
    )
  } yield finalState

  result match {
    case Right(finalState) =>
      println("\n" + "=" * 80)
      println("✅ Math question answered")
      println("=" * 80)
      println(s"Status: ${finalState.status}")
      println(s"\nAnswer:")
      println(finalState.conversation.messages.last.content)

      // Check if handoff occurred
      if (finalState.logs.exists(_.contains("handoff"))) {
        println(s"\n🔄 Handoff occurred:")
        finalState.logs.filter(_.contains("handoff")).foreach(log => println(s"  $log"))
      }

    case Left(error) =>
      println("\n" + "=" * 80)
      println("❌ Error occurred")
      println("=" * 80)
      println(s"Error: ${error.formatted}")
  }
}
