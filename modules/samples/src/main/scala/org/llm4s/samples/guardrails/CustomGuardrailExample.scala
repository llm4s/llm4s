package org.llm4s.samples.guardrails

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Custom guardrail that checks for specific keywords.
 *
 * This guardrail ensures that user queries contain required keywords,
 * which can be useful for topic-specific agents or filtering.
 */
class KeywordRequirementGuardrail(requiredKeywords: Set[String]) extends InputGuardrail {
  def validate(value: String): Result[String] = {
    val lowerValue      = value.toLowerCase
    val missingKeywords = requiredKeywords.filterNot(kw => lowerValue.contains(kw.toLowerCase))

    if (missingKeywords.isEmpty) {
      Right(value)
    } else {
      Left(
        ValidationError.invalid(
          "input",
          s"Query must contain keywords: ${missingKeywords.mkString(", ")}"
        )
      )
    }
  }

  val name                 = "KeywordRequirementGuardrail"
  override val description = Some(s"Requires keywords: ${requiredKeywords.mkString(", ")}")
}

/**
 * Example demonstrating custom guardrail implementation.
 *
 * This example shows how to create and use custom guardrails
 * to enforce application-specific validation rules.
 *
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
 */
object CustomGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Custom Guardrail Example ===")

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)

    // Create custom guardrail
    customGuardrail = new KeywordRequirementGuardrail(Set("scala", "programming"))

    // Run with custom guardrail
    agent <- Agent
      .builder("custom-guardrail", client)
      .withMiddleware(GuardrailMiddleware(Seq(customGuardrail), Seq.empty))
      .build()
    state <- agent.run("Tell me about Scala programming language features")
  } yield state

  result match {
    case Right(state) =>
      state.status match {
        case AgentStatus.Completed(answer) =>
          logger.info("✓ Query contained required keywords (scala, programming)")
          logger.info("Agent response:")
          answer.split("\n").take(5).foreach(line => logger.info("  {}", line))
        case other =>
          logger.warn("✗ Run did not complete: {}", AgentResults.describe(other))
      }

    case Left(error) =>
      logger.error("✗ Validation failed:")
      logger.error("  Error: {}", error.formatted)
  }

  logger.info("=" * 50)

  // Demonstrate failure case
  logger.info("=== Testing with missing keywords ===")

  val failureResult = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    customGuardrail = new KeywordRequirementGuardrail(Set("scala", "programming"))
    agent <- Agent
      .builder("custom-guardrail-missing", client)
      .withMiddleware(GuardrailMiddleware(Seq(customGuardrail), Seq.empty))
      .build()

    // This should be blocked - doesn't contain required keywords
    state <- agent.run("What's the weather like today?")
  } yield state

  failureResult match {
    case Right(state) =>
      state.status match {
        case AgentStatus.Blocked(guardrail, reason) =>
          logger.info("✓ Expected validation failure:")
          logger.info("  Blocked by {}: {}", guardrail, reason)
        case AgentStatus.Completed(_) => logger.warn("Unexpected success")
        case other                    => logger.warn("✗ Run did not complete: {}", AgentResults.describe(other))
      }

    case Left(error) =>
      logger.info("✓ Expected validation failure:")
      logger.info("  Error: {}", error.formatted)
  }

  logger.info("=" * 50)
}
