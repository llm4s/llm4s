package org.llm4s.samples.rag

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.rag.benchmark.{ BenchmarkReport, BenchmarkRunner, BenchmarkSuite, DatasetManager }
import org.llm4s.error.ConfigurationError

/**
 * CLI entrypoint for running RAG benchmarks from the samples module.
 *
 * Usage:
 * {{{
 * sbt '++3.7.1 samples/runMain org.llm4s.samples.rag.BenchmarkRunnerCli <dataset-path> [--quick]'
 * }}}
 *
 * Requires LLM_MODEL and embedding provider configuration via Llm4sConfig.
 */
object BenchmarkRunnerCli {

  def main(args: Array[String]): Unit = {
    val datasetPath = args.headOption.getOrElse {
      println("Usage: BenchmarkRunnerCli <dataset-path> [--quick]")
      println("\nChecking for available datasets...")

      val available = DatasetManager.checkDatasets()
      available.foreach { case (name, exists) =>
        val status = if (exists) "OK" else "MISSING"
        println(s"  [$status] $name")
      }

      if (available.values.forall(!_)) {
        println(DatasetManager.downloadInstructions)
      }

      sys.exit(1)
    }

    val isQuick = args.contains("--quick")

    println("Loading benchmark runner...")

    val runnerResult = for {
      providerCfg     <- Llm4sConfig.provider()
      llmClient       <- LLMConnect.getClient(providerCfg)
      embeddingResult <- Llm4sConfig.embeddings()
      (providerName, providerConfig) = embeddingResult
      embeddingClient <- EmbeddingClient.from(providerName, providerConfig)
      resolveEmbedding = (name: String) =>
        if (name.equalsIgnoreCase(providerName)) Right(providerConfig)
        else Left(ConfigurationError(s"No embedding credentials for provider '$name'"))
    } yield BenchmarkRunner(llmClient, embeddingClient, resolveEmbedding)

    runnerResult match {
      case Right(runner) =>
        val suite = if (isQuick) {
          BenchmarkSuite.quickSuite(datasetPath)
        } else {
          BenchmarkSuite.chunkingSuite(datasetPath)
        }

        println(s"Running suite: ${suite.name}")
        println(s"Experiments: ${suite.experiments.map(_.name).mkString(", ")}")

        runner.runSuite(suite) match {
          case Right(results) =>
            println(BenchmarkReport.console(results))
          case Left(error) =>
            println(s"Benchmark failed: ${error.message}")
            sys.exit(1)
        }

      case Left(error) =>
        println(s"Failed to initialize: ${error.message}")
        println("Ensure LLM_MODEL and embedding provider environment variables are set.")
        sys.exit(1)
    }
  }
}
