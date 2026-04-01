package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Demonstrates listing models in parallel for several named providers
 * configured under `llm4s.providers`.
 *
 * To run:
 *   sbt "samples/runMain org.llm4s.samples.basic.ParallelNamedProviderModelListingExample"
 */
object ParallelNamedProviderModelListingExample:
  private given ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit =
    val providerNames = List(
      "openai-main",
      "anthropic-main",
      "gemini-main",
      "deepseek-main",
      "mistral-main",
      "ollama-local"
    )

    val logger = LoggerFactory.getLogger("org.llm4s.samples.basic.ParallelNamedProviderModelListingExample")

    logger.info("=== Parallel Named Provider Model Listing Example ===")
    logger.info("Provider names: {}", providerNames.mkString(", "))

    val blocksFuture: Future[Seq[String]] =
      Future.traverse(providerNames): providerName =>
        runProvider(providerName)
          .map(result => formatProviderBlock(providerName, result))
          .recover { case throwable =>
            formatProviderBlock(
              providerName,
              Left(org.llm4s.error.UnknownError(s"Unexpected failure: ${throwable.getMessage}", throwable))
            )
          }

    val blocks = Await.result(blocksFuture, 5.minutes)
    blocks.foreach(logger.info(_))

  private def runProvider(
    providerName: String
  ): Future[
    Result[(org.llm4s.config.ProvidersConfigModel.NamedProviderConfig, List[org.llm4s.config.DiscoveredModel])]
  ] =
    Future:
      for
        providers <- Llm4sConfig.providers()
        named <- providers.namedProviders
          .get(ProviderName(providerName))
          .toRight(org.llm4s.error.ConfigurationError(s"Configured provider '$providerName' was not found"))
        models <- Llm4sConfig.listModels(providerName)
      yield (named, models)

  private def formatProviderBlock(
    providerName: String,
    result: Result[(org.llm4s.config.ProvidersConfigModel.NamedProviderConfig, List[org.llm4s.config.DiscoveredModel])]
  ): String =
    result.fold(
      err => s"""
           |
           |=== $providerName ===
           |Status: FAILED
           |Error: ${err.formatted}
           |""".stripMargin.trim,
      { case (named, models) =>
        val modelLines =
          models match
            case Nil => "Models: none"
            case all => all.map(model => s"- ${model.name.asString}").mkString("\n")

        s"""
           |
           |=== $providerName ===
           |Status: SUCCESS
           |Provider kind: ${named.provider.toString.toLowerCase}
           |Configured model: ${named.model.asString}
           |Discovered ${models.size} models
           |$modelLines
           |""".stripMargin.trim
      }
    )
