package samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.slf4j.LoggerFactory

/**
 * Demonstrates listing models serially for several named providers configured
 * under `llm4s.providers`.
 *
 * To run:
 *   sbt "samples/runMain samples.basic.SerialNamedProviderModelListingExample"
 */
object SerialNamedProviderModelListingExample:
  def main(args: Array[String]): Unit =
    val providerNames = List(
      "openai-main",
      "anthropic-main",
      "gemini-main",
      "deepseek-main",
      "mistral-main",
      "ollama-local"
    )

    val logger = LoggerFactory.getLogger("samples.basic.SerialNamedProviderModelListingExample")

    logger.info("=== Serial Named Provider Model Listing Example ===")
    logger.info("Provider names: {}", providerNames.mkString(", "))

    providerNames.foreach: providerName =>
      val result = for
        providers <- Llm4sConfig.providers()
        named <- providers.namedProviders
          .get(ProviderName(providerName))
          .toRight(org.llm4s.error.ConfigurationError(s"Configured provider '$providerName' was not found"))
        models <- Llm4sConfig.listModels(providerName)
      yield (named, models)

      logger.info("")
      logger.info("=== {} ===", providerName)

      result.fold(
        err =>
          logger.error("Status: FAILED")
          logger.error("Error: {}", err.formatted)
        ,
        { case (named, models) =>
          logger.info("Status: SUCCESS")
          logger.info("Provider kind: {}", named.provider.toString.toLowerCase)
          logger.info("Configured model: {}", named.model.asString)
          logger.info("Discovered {} models", models.size)
          models.foreach: model =>
            logger.info("- {}", model.name.asString)
        }
      )
