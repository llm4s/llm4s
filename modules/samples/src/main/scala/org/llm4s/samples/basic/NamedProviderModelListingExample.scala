package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.slf4j.LoggerFactory

/**
 * Demonstrates listing models for a named provider through the new
 * named-provider configuration path and provider capabilities.
 *
 * This example currently targets `ollama-local`, because Ollama is the first
 * provider with model discovery support.
 *
 * To run:
 *   sbt "samples/runMain org.llm4s.samples.basic.NamedProviderModelListingExample"
 */
object NamedProviderModelListingExample:
  def main(args: Array[String]): Unit =
    val providerName = "ollama-local"
    val logger       = LoggerFactory.getLogger("org.llm4s.samples.basic.NamedProviderModelListingExample")

    val result = for
      providers <- Llm4sConfig.providers()
      namedConfig <- providers.namedProviders
        .get(org.llm4s.config.ProvidersConfigModel.ProviderName(providerName))
        .toRight(org.llm4s.error.ConfigurationError(s"Configured provider '$providerName' was not found"))
      models <- Llm4sConfig.listModels(providerName)
    yield (namedConfig, models)

    result.fold(
      err =>
        logger.error("Failed to list models for named provider '{}': {}", providerName, err.formatted)
        logger.info("Check the named provider entry in application.local.conf and whether the provider is reachable.")
      ,
      { case (config, models) =>
        logger.info("=== Named Provider Model Listing Example ===")
        logger.info("Provider name: {}", providerName)
        logger.info("Provider kind: {}", config.provider.toString.toLowerCase)
        logger.info("Configured model: {}", config.model.asString)
        logger.info("Discovered {} models", models.size)

        models.foreach: model =>
          logger.info("")
          logger.info("=== {} ===", model.name.asString)
          logger.info("Provider: {}", model.provider)
          if model.metadata.isEmpty then logger.info("Metadata: none")
          else
            model.metadata.toSeq
              .sortBy(_._1)
              .foreach: (key, value) =>
                logger.info("{}: {}", key, value)
      }
    )
