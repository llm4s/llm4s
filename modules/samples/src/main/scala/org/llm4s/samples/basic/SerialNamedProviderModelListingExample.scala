package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.slf4j.LoggerFactory

/**
 * Demonstrates listing models serially for every named provider configured
 * under `llm4s.providers`.
 *
 * Sections are loaded with `Llm4sConfig.providerConfigs()`, which loads each section on its
 * own: a section that fails to load - an unset `${?VAR}` key, a provider whose module is not on
 * the classpath - is reported as FAILED with its error, and the others are still listed.
 *
 * To run:
 *   sbt "samples/runMain org.llm4s.samples.basic.SerialNamedProviderModelListingExample"
 */
object SerialNamedProviderModelListingExample:
  def main(args: Array[String]): Unit =
    val logger = LoggerFactory.getLogger("org.llm4s.samples.basic.SerialNamedProviderModelListingExample")

    logger.info("=== Serial Named Provider Model Listing Example ===")

    Llm4sConfig.providerConfigs() match
      case Left(err) =>
        logger.error("Could not read llm4s.providers: {}", err.formatted)

      case Right((errors, configs)) =>
        val sections =
          (errors.toList.map((name, err) => name.asName -> Left(err)) ++
            configs.toList.map((name, config) => name.asName -> Right(config))).sortBy(_._1)
        logger.info("Configured sections: {}", sections.map(_._1).mkString(", "))

        sections.foreach: (providerName, section) =>
          logger.info("")
          logger.info("=== {} ===", providerName)

          section match
            case Left(err) =>
              logger.error("Status: FAILED - the section did not load")
              logger.error("Error: {}", err.formatted)

            case Right(config) =>
              Llm4sConfig.listModels(providerName) match
                case Left(err) =>
                  logger.error("Status: FAILED - model listing")
                  logger.error("Error: {}", err.formatted)
                case Right(models) =>
                  logger.info("Status: SUCCESS")
                  logger.info("Provider kind: {}", config.providerId.asString)
                  logger.info("Configured model: {}", config.model)
                  logger.info("Discovered {} models", models.size)
                  models.foreach: model =>
                    logger.info("- {}", model.name.asString)
