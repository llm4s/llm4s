package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Example demonstrating JSON output validation with guardrails.
 *
 * This example shows how to ensure that agent output is valid JSON,
 * which is useful when requesting structured data from the LLM.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object JSONOutputValidationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== JSON Output Validation Example ===")

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // Define output guardrails
    outputGuardrails = Seq(
      new JSONValidator()
    )

    // Request JSON output with validation
    state <- agent.run(
      query = """Generate a JSON object with the following fields:
                |{
                |  "name": "Scala",
                |  "paradigm": "functional and object-oriented",
                |  "year": 2004
                |}
                |Return ONLY the JSON, no other text.""".stripMargin,
      tools = new ToolRegistry(Seq.empty),
      outputGuardrails = outputGuardrails
    )
  } yield state

  result match {
    case Right(state) =>
      logger.info("✓ Output validation passed - response is valid JSON!")

      val response = state.conversation.messages.last.content
      logger.info("JSON Response:")
      logger.info("{}", response)

      // Can safely parse the JSON now
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

    case Left(error) =>
      logger.error("✗ Validation or execution failed:")
      logger.error("  Error: {}", error.formatted)
      logger.info("This error likely means the LLM did not return valid JSON.")
  }

  logger.info("=" * 50)
}
