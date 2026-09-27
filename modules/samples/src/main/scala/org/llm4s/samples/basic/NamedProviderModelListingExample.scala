package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.error.ConfigurationError
import org.slf4j.LoggerFactory

/**
 * Demonstrates listing models for a named provider through the new
 * named-provider configuration path and provider capabilities.
 *
 * This example currently targets `ollama-local`, because Ollama is the first
 * provider with model discovery support.
 *
 * Sections are loaded with `Llm4sConfig.providerConfigs()`, which loads each section on its
 * own, so a broken section elsewhere in the config - an unset `${?VAR}` key, say - does not
 * stop this one from being listed. The broken sections' errors are logged, not hidden.
 *
 * To run:
 *   sbt "samples/runMain org.llm4s.samples.basic.NamedProviderModelListingExample"
 */
object NamedProviderModelListingExample:
  def main(args: Array[String]): Unit =
    val providerName = ProviderName("ollama-local")
    val logger       = LoggerFactory.getLogger("org.llm4s.samples.basic.NamedProviderModelListingExample")

    val result = for
      loaded <- Llm4sConfig.providerConfigs()
      (errors, configs) = loaded
      _ = errors.toSeq
        .sortBy(_._1.asName)
        .foreach((name, err) => logger.warn("Section '{}' did not load: {}", name.asName, err.formatted))
      config <- configs
        .get(providerName)
        .toRight(
          errors.getOrElse(
            providerName,
            ConfigurationError(s"Configured provider '${providerName.asName}' was not found")
          )
        )
      models <- Llm4sConfig.listModels(providerName.asName)
    yield (config, models)

    result.fold(
      err =>
        logger.error("Failed to list models for named provider '{}': {}", providerName.asName, err.formatted)
        logger.info("Check the named provider entry in application.local.conf and whether the provider is reachable.")
      ,
      { case (config, models) =>
        logger.info("=== Named Provider Model Listing Example ===")
        logger.info("Provider name: {}", providerName.asName)
        logger.info("Provider kind: {}", config.providerId.asString)
        logger.info("Configured model: {}", config.model)
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
