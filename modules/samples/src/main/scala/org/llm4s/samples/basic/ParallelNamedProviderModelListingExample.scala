package org.llm4s.samples.basic

import org.llm4s.config.{ DiscoveredModel, Llm4sConfig }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Demonstrates listing models in parallel for every named provider
 * configured under `llm4s.providers`.
 *
 * Sections are loaded with `Llm4sConfig.providerConfigs()`, which loads each section on its
 * own: a section that fails to load - an unset `${?VAR}` key, a provider whose module is not on
 * the classpath - is reported as FAILED with its error, and the others are still listed.
 *
 * To run:
 *   sbt "samples/runMain org.llm4s.samples.basic.ParallelNamedProviderModelListingExample"
 */
object ParallelNamedProviderModelListingExample:
  private given ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit =
    val logger = LoggerFactory.getLogger("org.llm4s.samples.basic.ParallelNamedProviderModelListingExample")

    logger.info("=== Parallel Named Provider Model Listing Example ===")

    Llm4sConfig.providerConfigs() match
      case Left(err) =>
        logger.error("Could not read llm4s.providers: {}", err.formatted)

      case Right((errors, configs)) =>
        val sections: List[(String, Result[ProviderConfig])] =
          (errors.toList.map((name, err) => name.asName -> Left(err)) ++
            configs.toList.map((name, config) => name.asName -> Right(config))).sortBy(_._1)
        logger.info("Configured sections: {}", sections.map(_._1).mkString(", "))

        val blocksFuture: Future[Seq[String]] =
          Future.traverse(sections) { (providerName, section) =>
            runProvider(providerName, section)
              .map(result => formatProviderBlock(providerName, result))
              .recover { case throwable =>
                formatProviderBlock(
                  providerName,
                  Left(org.llm4s.error.UnknownError(s"Unexpected failure: ${throwable.getMessage}", throwable))
                )
              }
          }

        val blocks = Await.result(blocksFuture, 5.minutes)
        blocks.foreach(logger.info(_))

  private def runProvider(
    providerName: String,
    section: Result[ProviderConfig]
  ): Future[Result[(ProviderConfig, List[DiscoveredModel])]] =
    Future:
      for
        config <- section
        models <- Llm4sConfig.listModels(providerName)
      yield (config, models)

  private def formatProviderBlock(
    providerName: String,
    result: Result[(ProviderConfig, List[DiscoveredModel])]
  ): String =
    result.fold(
      err => s"""
           |
           |=== $providerName ===
           |Status: FAILED
           |Error: ${err.formatted}
           |""".stripMargin.trim,
      { case (config, models) =>
        val modelLines =
          models match
            case Nil => "Models: none"
            case all => all.map(model => s"- ${model.name.asString}").mkString("\n")

        s"""
           |
           |=== $providerName ===
           |Status: SUCCESS
           |Provider kind: ${config.providerId.asString}
           |Configured model: ${config.model}
           |Discovered ${models.size} models
           |$modelLines
           |""".stripMargin.trim
      }
    )
