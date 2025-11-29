package org.llm4s.samples.rag

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.rag.benchmark._
import org.llm4s.error.ConfigurationError

/**
 * Example demonstrating the RAG benchmarking harness.
 *
 * This sample shows how to:
 * - Run benchmark suites comparing different configurations
 * - Generate detailed reports
 * - Compare chunking strategies, fusion methods, and embeddings
 *
 * Run with:
 * {{{
 * # First, download sample datasets
 * ./scripts/download-datasets.sh sample
 *
 * # Run quick benchmark
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-...
 * export EMBEDDING_PROVIDER=openai
 * export OPENAI_EMBEDDING_MODEL=text-embedding-3-small
 * sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample"
 *
 * # Or run with --quick flag for faster execution
 * sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --quick"
 * }}}
 */
object BenchmarkExample {

  def main(args: Array[String]): Unit = {
    println("=" * 70)
    println("RAG Benchmarking Harness Example")
    println("=" * 70)
    println()

    // Parse arguments
    val isQuick    = args.contains("--quick")
    val showHelp   = args.contains("--help") || args.contains("-h")
    val suiteArg   = args.dropWhile(_ != "--suite").drop(1).headOption
    val customPath = args.find(a => !a.startsWith("--") && a != suiteArg.getOrElse(""))

    if (showHelp) {
      printUsage()
      sys.exit(0)
    }

    // Check dataset availability
    println("Checking dataset availability...")
    val datasets = DatasetManager.checkDatasets()
    datasets.foreach { case (name, available) =>
      val status = if (available) "✓" else "✗"
      println(s"  $status $name")
    }
    println()

    // Determine dataset path
    val datasetPath = customPath.getOrElse {
      if (java.nio.file.Files.exists(java.nio.file.Paths.get(DatasetManager.Paths.ragbenchTest))) {
        DatasetManager.Paths.ragbenchTest
      } else {
        println("No dataset found. Creating sample data...")
        createSampleDataset()
        "data/datasets/ragbench/test.jsonl"
      }
    }

    println(s"Using dataset: $datasetPath")
    println()

    // Initialize benchmark runner
    val runnerResult = for {
      providerCfg     <- Llm4sConfig.provider()
      llmClient       <- LLMConnect.getClient(providerCfg)
      embeddingResult <- Llm4sConfig.embeddings()
      (providerName, embCfg) = embeddingResult
      embeddingClient <- EmbeddingClient.from(providerName, embCfg)
      resolveEmbedding = (name: String) =>
        if (name.equalsIgnoreCase(providerName)) Right(embCfg)
        else Left(ConfigurationError(s"No embedding credentials for provider '$name'"))
    } yield BenchmarkRunner(llmClient, embeddingClient, resolveEmbedding)

    runnerResult match {
      case Right(runner) =>
        runBenchmark(runner, datasetPath, isQuick, suiteArg)

      case Left(error) =>
        println(s"❌ Failed to initialize: ${error.message}")
        println()
        println("Required environment variables:")
        println("  LLM_MODEL=openai/gpt-4o (or anthropic/claude-sonnet-4-5-latest)")
        println("  OPENAI_API_KEY=sk-...")
        println("  EMBEDDING_PROVIDER=openai")
        println("  OPENAI_EMBEDDING_MODEL=text-embedding-3-small")
        sys.exit(1)
    }
  }

  def runBenchmark(
    runner: BenchmarkRunner,
    datasetPath: String,
    quick: Boolean,
    suiteArg: Option[String]
  ): Unit = {
    // Choose suite based on mode and argument
    val suite = suiteArg match {
      case Some("chunking") =>
        println("Running chunking comparison benchmark...")
        if (quick) BenchmarkSuite.chunkingSuite(datasetPath).quick(5)
        else BenchmarkSuite.chunkingSuite(datasetPath)
      case Some("fusion") =>
        println("Running fusion strategy comparison benchmark...")
        if (quick) BenchmarkSuite.fusionSuite(datasetPath).quick(5)
        else BenchmarkSuite.fusionSuite(datasetPath)
      case Some("embedding") =>
        println("Running embedding provider comparison benchmark...")
        if (quick) BenchmarkSuite.embeddingSuite(datasetPath).quick(5)
        else BenchmarkSuite.embeddingSuite(datasetPath)
      case Some("all") =>
        println("Running all benchmark suites...")
        // Run chunking first, then create combined results
        if (quick) BenchmarkSuite.chunkingSuite(datasetPath).quick(5)
        else BenchmarkSuite.chunkingSuite(datasetPath)
      case _ =>
        if (quick) {
          println("Running quick benchmark (5 samples)...")
          BenchmarkSuite.quickSuite(datasetPath, 5)
        } else {
          println("Running chunking comparison benchmark...")
          BenchmarkSuite.chunkingSuite(datasetPath)
        }
    }

    println(s"Suite: ${suite.name}")
    println(s"Experiments: ${suite.experiments.map(_.name).mkString(", ")}")
    println()

    // Run the benchmark
    val startTime = System.currentTimeMillis()

    runner.runSuite(suite) match {
      case Right(results) =>
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

        // Print console report
        println(BenchmarkReport.console(results))

        // Save JSON report
        val jsonPath = s"data/results/${suite.name}-${System.currentTimeMillis()}.json"
        BenchmarkReport.save(results, jsonPath, ReportFormat.Json) match {
          case Right(_) => println(s"Report saved to: $jsonPath")
          case Left(e)  => println(s"Failed to save report: ${e.message}")
        }

        // Print summary
        println()
        println(f"Total benchmark time: $elapsed%.1fs")

        results.winner.foreach { winner =>
          println()
          println("=" * 70)
          println(s"RECOMMENDATION: Use '${winner.config.name}' configuration")
          println(s"               ${winner.config.fullDescription}")
          println("=" * 70)
        }

      case Left(error) =>
        println(s"❌ Benchmark failed: ${error.message}")
        sys.exit(1)
    }
  }

  def createSampleDataset(): Unit = {
    import java.nio.file.{ Files, Paths }

    val dir = Paths.get("data/datasets/ragbench")
    Files.createDirectories(dir)

    val sampleData =
      """{"question": "What is machine learning?", "response": "Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience.", "documents": ["Machine learning is a branch of artificial intelligence (AI) that enables computers to learn from data and improve their performance without being explicitly programmed."], "answer": "Machine learning is a subset of AI that allows systems to learn from data."}
        |{"question": "What is the capital of France?", "response": "Paris is the capital of France.", "documents": ["Paris is the capital and largest city of France. It is located in the north-central part of the country."], "answer": "Paris is the capital of France."}
        |{"question": "How does photosynthesis work?", "response": "Photosynthesis is the process by which plants convert sunlight, water, and carbon dioxide into glucose and oxygen.", "documents": ["Photosynthesis is a biological process used by plants to convert light energy into chemical energy."], "answer": "Photosynthesis converts sunlight, water, and CO2 into glucose and oxygen."}
        |{"question": "What causes earthquakes?", "response": "Earthquakes are caused by the movement of tectonic plates beneath the Earth's surface.", "documents": ["Earthquakes occur when tectonic plates shift along fault lines. The sudden release of energy creates seismic waves."], "answer": "Earthquakes are caused by tectonic plate movement."}
        |{"question": "What is the theory of relativity?", "response": "Einstein's theory of relativity describes how space, time, and gravity are interconnected.", "documents": ["Einstein's special theory of relativity introduced the concept that the speed of light is constant and that time and space are relative."], "answer": "Relativity describes the relationship between space, time, and gravity."}""".stripMargin

    Files.write(dir.resolve("test.jsonl"), sampleData.getBytes)
    println("Sample dataset created at data/datasets/ragbench/test.jsonl")
  }

  def printUsage(): Unit =
    println("""
      |Usage: BenchmarkExample [options] [dataset-path]
      |
      |Options:
      |  --quick     Run with minimal samples for fast validation
      |  --help, -h  Show this help message
      |
      |Examples:
      |  # Run full chunking comparison
      |  sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample"
      |
      |  # Run quick test with 5 samples
      |  sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --quick"
      |
      |  # Use custom dataset
      |  sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample data/my-dataset.jsonl"
      |
      |Environment Variables:
      |  LLM_MODEL              LLM model to use (e.g., openai/gpt-4o)
      |  OPENAI_API_KEY         OpenAI API key
      |  EMBEDDING_PROVIDER     Embedding provider (openai, voyage, ollama)
      |  OPENAI_EMBEDDING_MODEL Embedding model name
      |
      |Available Benchmark Suites:
      |  - chunkingSuite:  Compare Simple, Sentence, Markdown chunking
      |  - fusionSuite:    Compare RRF, Weighted, Vector-only, Keyword-only
      |  - embeddingSuite: Compare OpenAI, Voyage, Ollama embeddings
      |  - quickSuite:     Fast validation with 10 samples
      |""".stripMargin)
}
