package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example of a plain agent run with a step limit: it prints the messages the run produced.
 * Step-level events are planned: see #1329.
 */
object SingleStepAgentExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      agent <- Agent
        .builder("single-step-agent", client)
        .withTools(new ToolRegistry(Seq(weatherTool)))
        .withMaxSteps(5)
        .build()

      query = "I'm planning a trip to Paris. What's the weather like there now?"
        .tap(q => logger.info("User Query: {}", q))

      _ = logger.info("=== Running Agent ===")

      finalResult <- agent.run(query)

      _ = logger.info("=== Run Complete ===")
      _ = logger.info("Final status: {}", finalResult.status)
      _ = logger.info("Total messages: {}", finalResult.messages.length)
      _ = finalResult.messages.foreach { msg =>
        val preview = msg.content.take(100) + (if (msg.content.length > 100) "..." else "")
        logger.info("[{}] {}", msg.role, preview)
      }

    } yield ()

    result.fold(
      err => logger.error("Error: {}", err.formatted),
      identity
    )
  }
}
