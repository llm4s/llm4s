package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.*
import org.slf4j.LoggerFactory

/**
 * Demonstrates the new named-providers configuration path by:
 *  - loading the configured default provider name from `llm4s.providers.provider`
 *  - loading the corresponding default [[org.llm4s.llmconnect.config.ProviderConfig]]
 *  - sending a single prompt
 *  - printing the response
 *
 * To run:
 *   sbt "samples/runMain org.llm4s.samples.basic.DefaultNamedProviderExample"
 */
object DefaultNamedProviderExample:
  def main(args: Array[String]): Unit =
    val prompt =
      args.headOption.getOrElse("Reply in one short sentence: who are you?")

    val logger = LoggerFactory.getLogger("org.llm4s.samples.basic.DefaultNamedProviderExample")

    val conversation = Conversation(
      Seq(
        UserMessage(prompt)
      )
    )

    val result = for
      defaultProviderName <- Llm4sConfig.defaultProviderName()
      providerCfg         <- Llm4sConfig.defaultProvider()
      registryService     <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client     <- LLMConnect.getClient(providerCfg)
      completion <- client.complete(conversation)
    yield (defaultProviderName, providerCfg, completion)

    result.fold(
      err =>
        logger.error("Failed to run default named provider example: {}", err.formatted)
        logger.info("Check llm4s.providers.provider and the named provider entries in application.local.conf.")
      ,
      { case (providerName, cfg, completion) =>
        logger.info("=== Default Named Provider Example ===")
        logger.info("Selected provider name: {}", providerName)
        logger.info("Configured model: {}", cfg.model)
        logger.info("Completion model: {}", completion.model)
        logger.info("--- Prompt ---")
        logger.info("{}", prompt)
        logger.info("--- Response ---")
        logger.info("{}", completion.message.content)
        logger.info("--- End Response ---")
        completion.usage.foreach: usage =>
          logger.info(
            "Tokens used: {} ({} prompt + {} completion)",
            usage.totalTokens,
            usage.promptTokens,
            usage.completionTokens
          )
      }
    )
