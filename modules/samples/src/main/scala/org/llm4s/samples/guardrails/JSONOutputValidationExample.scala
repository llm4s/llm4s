package org.llm4s.samples.guardrails

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Example demonstrating JSON output validation with guardrails.
 *
 * This example shows how to ensure that agent output is valid JSON,
 * which is useful when requesting structured data from the LLM.
 *
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
 */
object JSONOutputValidationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  // An output guardrail that fails ends the run as AgentStatus.Blocked, not a Left.
  private def guarded(
    client: org.llm4s.llmconnect.LLMClient,
    id: String,
    guardrails: Seq[org.llm4s.agent.guardrails.OutputGuardrail],
    query: String
  ): org.llm4s.types.Result[AgentResult] =
    Agent
      .builder(id, client)
      .withMiddleware(GuardrailMiddleware(Seq.empty, guardrails))
      .build()
      .flatMap(_.run(query))

  logger.info("=== JSON Output Validation Example ===")

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    // Define output guardrails
    outputGuardrails = Seq(
      new JSONValidator()
    )

    // Request JSON output with validation
    state <- guarded(
      client,
      "json-output-validation",
      outputGuardrails,
      """Generate a JSON object with the following fields:
        |{
        |  "name": "Scala",
        |  "paradigm": "functional and object-oriented",
        |  "year": 2004
        |}
        |Return ONLY the JSON, no other text.""".stripMargin
    )
  } yield state

  result match {
    case Right(state) =>
      state.status match {
        case AgentStatus.Completed(response) =>
          logger.info("✓ Output validation passed - response is valid JSON!")
          logger.info("JSON Response:")
          logger.info("{}", response)
          parseFields(response)
        case other =>
          logger.error("✗ Run did not complete: {}", AgentResults.describe(other))
          logger.info("If blocked, the LLM likely did not return valid JSON.")
      }

    case Left(error) =>
      logger.error("✗ Validation or execution failed:")
      logger.error("  Error: {}", error.formatted)
      logger.info("This error likely means the LLM did not return valid JSON.")
  }

  logger.info("=" * 50)

  // Can safely parse the JSON now
  private def parseFields(response: String): Unit = {
    import scala.util.Try
    import org.llm4s.types.TryOps

    Try {
      val json = ujson.read(response)
      logger.info("Parsed JSON fields:")
      logger.info("  Name: {}", json("name").str)
      logger.info("  Paradigm: {}", json("paradigm").str)
      logger.info("  Year: {}", json("year").num.toInt)
    }.toResult match {
      case Right(_) => // Successfully parsed
      case Left(error) =>
        logger.warn("  Note: Could not parse all fields: {}", error.message)
    }
  }
}
