package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails._
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

/**
 * Example demonstrating composite guardrails with different validation modes.
 *
 * This example shows how to combine multiple guardrails using different
 * composition strategies (All, Any, Sequential) to create complex validation logic.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object CompositeGuardrailExample extends App {
  println("=== Composite Guardrail Example ===\n")

  // Example 1: All guardrails must pass (AND logic)
  println("Example 1: All guardrails must pass\n")

  val safetyChecks = CompositeGuardrail.all(
    Seq(
      new LengthCheck(min = 1, max = 10000),
      new ProfanityFilter()
    )
  )

  val result1 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(safetyChecks.asInstanceOf[InputGuardrail])
    )
  } yield state

  result1 match {
    case Right(_) =>
      println("✓ All safety checks passed!")
      println(s"  Length check: PASS")
      println(s"  Profanity filter: PASS\n")

    case Left(error) =>
      println(s"✗ Validation failed: ${error.formatted}\n")
  }

  // Example 2: At least one guardrail must pass (OR logic)
  println("Example 2: At least one guardrail must pass (language detection)\n")

  val languageDetection = CompositeGuardrail.any(
    Seq(
      new RegexValidator(".*\\b(scala|functional)\\b.*".r),
      new RegexValidator(".*\\b(java|object-oriented)\\b.*".r),
      new RegexValidator(".*\\b(python|dynamic)\\b.*".r)
    )
  )

  val result2 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(languageDetection.asInstanceOf[InputGuardrail])
    )
  } yield state

  result2 match {
    case Right(_) =>
      println("✓ Query matched at least one language pattern!")
      println(s"  (Contains: scala, functional, java, python, etc.)\n")

    case Left(error) =>
      println(s"✗ No language patterns matched: ${error.formatted}\n")
  }

  // Example 3: Sequential validation (short-circuit on failure)
  println("Example 3: Sequential validation with early termination\n")

  val sequentialChecks = CompositeGuardrail.sequential(
    Seq(
      new LengthCheck(min = 1, max = 10000), // Check this first (cheap)
      new ProfanityFilter()                  // Only check if length passes
    )
  )

  val result3 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "What is functional programming?",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(sequentialChecks.asInstanceOf[InputGuardrail])
    )
  } yield state

  result3 match {
    case Right(_) =>
      println("✓ All sequential checks passed!")
      println(s"  Step 1 (Length): PASS")
      println(s"  Step 2 (Profanity): PASS\n")

    case Left(error) =>
      println(s"✗ Validation failed at some step: ${error.formatted}\n")
  }

  // Example 4: Combining safety and business logic
  println("Example 4: Combining safety and business logic\n")

  val combinedValidation: InputGuardrail = new InputGuardrail {
    val safetyLayer = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 10000),
        new ProfanityFilter()
      )
    )

    val businessLayer = CompositeGuardrail.any(
      Seq(
        new RegexValidator(".*\\b(scala|java|python|rust)\\b.*".r)
      )
    )

    def validate(value: String): org.llm4s.types.Result[String] =
      for {
        safeInput  <- safetyLayer.validate(value)
        validInput <- businessLayer.validate(safeInput)
      } yield validInput

    val name                 = "CombinedValidation"
    override val description = Some("Safety checks + business logic")
  }

  val result4 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming best practices",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(combinedValidation)
    )
  } yield state

  result4 match {
    case Right(state) =>
      println("✓ Combined validation passed!")
      println(s"  Safety layer: PASS")
      println(s"  Business layer: PASS")
      println("\nResponse preview:")
      state.conversation.messages.last.content.split("\n").take(3).foreach(line => println(s"  $line"))

    case Left(error) =>
      println(s"✗ Combined validation failed: ${error.formatted}")
  }

  println("\n" + "=" * 50)
}
