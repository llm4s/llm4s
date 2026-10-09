package org.llm4s.samples.guardrails

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails._
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Example demonstrating composite guardrails with different validation modes.
 *
 * This example shows how to combine multiple guardrails using different
 * composition strategies (All, Any, Sequential) to create complex validation logic.
 *
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
 */
object CompositeGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Composite Guardrail Example ===")

  // A composite is a `Guardrail[String]`, not an `InputGuardrail`, and `GuardrailMiddleware` takes input
  // guardrails: wrap the composite (a cast would throw a ClassCastException when the agent is built).
  private def asInput(guardrail: Guardrail[String]): InputGuardrail = new InputGuardrail {
    val name: String                                            = guardrail.name
    def validate(value: String): org.llm4s.types.Result[String] = guardrail.validate(value)
  }

  // A blocked input is a successful run whose status is Blocked, not a Left; so is any run that
  // did not reach an answer.
  private def blocked(result: org.llm4s.agent.AgentResult): Boolean =
    !result.status.isInstanceOf[AgentStatus.Completed]

  private def reason(result: org.llm4s.agent.AgentResult): String = AgentResults.describe(result.status)

  // Example 1: All guardrails must pass (AND logic)
  logger.info("Example 1: All guardrails must pass")

  val safetyChecks = CompositeGuardrail.all(
    Seq(
      new LengthCheck(min = 1, max = 10000),
      new ProfanityFilter()
    )
  )

  val result1 = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    agent <- Agent
      .builder("composite-guardrail-1", client)
      .withMiddleware(GuardrailMiddleware(Seq(asInput(safetyChecks)), Seq.empty))
      .build()
    state <- agent.run("Tell me about Scala programming")
  } yield state

  result1 match {
    case Right(state) if blocked(state) =>
      logger.error("✗ Validation failed: {}", reason(state))
    case Right(_) =>
      logger.info("✓ All safety checks passed!")
      logger.info("  Length check: PASS")
      logger.info("  Profanity filter: PASS")

    case Left(error) =>
      logger.error("✗ Validation failed: {}", error.formatted)
  }

  // Example 2: At least one guardrail must pass (OR logic)
  logger.info("Example 2: At least one guardrail must pass (language detection)")

  val languageDetection = CompositeGuardrail.any(
    Seq(
      new RegexValidator(".*\\b(scala|functional)\\b.*".r),
      new RegexValidator(".*\\b(java|object-oriented)\\b.*".r),
      new RegexValidator(".*\\b(python|dynamic)\\b.*".r)
    )
  )

  val result2 = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    agent <- Agent
      .builder("composite-guardrail-2", client)
      .withMiddleware(GuardrailMiddleware(Seq(asInput(languageDetection)), Seq.empty))
      .build()
    state <- agent.run("Tell me about Scala programming")
  } yield state

  result2 match {
    case Right(state) if blocked(state) =>
      logger.error("✗ No language patterns matched: {}", reason(state))
    case Right(_) =>
      logger.info("✓ Query matched at least one language pattern!")
      logger.info("  (Contains: scala, functional, java, python, etc.)")

    case Left(error) =>
      logger.error("✗ No language patterns matched: {}", error.formatted)
  }

  // Example 3: Sequential validation (short-circuit on failure)
  logger.info("Example 3: Sequential validation with early termination")

  val sequentialChecks = CompositeGuardrail.sequential(
    Seq(
      new LengthCheck(min = 1, max = 10000), // Check this first (cheap)
      new ProfanityFilter()                  // Only check if length passes
    )
  )

  val result3 = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    agent <- Agent
      .builder("composite-guardrail-3", client)
      .withMiddleware(GuardrailMiddleware(Seq(asInput(sequentialChecks)), Seq.empty))
      .build()
    state <- agent.run("What is functional programming?")
  } yield state

  result3 match {
    case Right(state) if blocked(state) =>
      logger.error("✗ Validation failed at some step: {}", reason(state))
    case Right(_) =>
      logger.info("✓ All sequential checks passed!")
      logger.info("  Step 1 (Length): PASS")
      logger.info("  Step 2 (Profanity): PASS")

    case Left(error) =>
      logger.error("✗ Validation failed at some step: {}", error.formatted)
  }

  // Example 4: Combining safety and business logic
  logger.info("Example 4: Combining safety and business logic")

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
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    agent <- Agent
      .builder("composite-guardrail-4", client)
      .withMiddleware(GuardrailMiddleware(Seq(combinedValidation), Seq.empty))
      .build()
    state <- agent.run("Tell me about Scala programming best practices")
  } yield state

  result4 match {
    case Right(state) if blocked(state) =>
      logger.error("✗ Combined validation failed: {}", reason(state))
    case Right(state) =>
      logger.info("✓ Combined validation passed!")
      logger.info("  Safety layer: PASS")
      logger.info("  Business layer: PASS")
      logger.info("Response preview:")
      AgentResults.answerOrStatus(state).split("\n").take(3).foreach(line => logger.info("  {}", line))

    case Left(error) =>
      logger.error("✗ Combined validation failed: {}", error.formatted)
  }

  logger.info("=" * 50)
}
