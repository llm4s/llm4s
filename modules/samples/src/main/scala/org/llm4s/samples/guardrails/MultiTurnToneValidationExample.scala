package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

/**
 * Example demonstrating multi-turn conversations with tone validation.
 *
 * This example shows how to apply consistent guardrails across
 * multiple turns of a conversation, including tone validation
 * to ensure professional communication.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object MultiTurnToneValidationExample extends App {
  println("=== Multi-Turn Conversation with Tone Validation ===\n")

  // Define guardrails to use across all turns
  val inputGuardrails = Seq(
    new LengthCheck(min = 1, max = 5000),
    new ProfanityFilter()
  )

  val outputGuardrails = Seq(
    new ToneValidator(allowedTones = Set(Tone.Professional, Tone.Friendly))
  )

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // Turn 1: Ask about Scala
    _ = println("Turn 1: Asking about Scala...")
    state1 <- agent.run(
      "What is Scala?",
      new ToolRegistry(Seq.empty),
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
    _ = println(s"  ✓ Response passed tone validation\n")

    // Turn 2: Ask for details
    _ = println("Turn 2: Asking for main features...")
    state2 <- agent.continueConversation(
      state1,
      "What are its main features?",
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
    _ = println(s"  ✓ Response passed tone validation\n")

    // Turn 3: Ask for examples
    _ = println("Turn 3: Asking for code example...")
    state3 <- agent.continueConversation(
      state2,
      "Can you give me a code example?",
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
    _ = println(s"  ✓ Response passed tone validation\n")

  } yield state3

  result match {
    case Right(finalState) =>
      println("✓ All turns completed successfully!")
      println(s"\nFinal conversation stats:")
      println(s"  Status: ${finalState.status}")
      println(s"  Total messages: ${finalState.conversation.messages.length}")
      println(s"  Turns completed: 3")

      println("\nFinal response:")
      finalState.conversation.messages.last.content.split("\n").take(5).foreach(line => println(s"  $line"))

    case Left(error) =>
      println(s"✗ Validation or execution failed:")
      println(s"  Error: ${error.formatted}")
      println("\nThis could mean:")
      println("  - Input validation failed (profanity or length)")
      println("  - Output tone was not professional or friendly")
      println("  - Agent execution error")
  }

  println("\n" + "=" * 50)
}
