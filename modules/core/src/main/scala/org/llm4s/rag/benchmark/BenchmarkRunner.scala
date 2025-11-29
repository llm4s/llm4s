package org.llm4s.rag.benchmark

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.rag.evaluation._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Main execution engine for RAG benchmarks.
 *
 * Orchestrates the full benchmark workflow:
 * 1. Load dataset
 * 2. For each experiment configuration:
 *    a. Create RAG pipeline with config
 *    b. Index documents
 *    c. Run queries and generate answers
 *    d. Evaluate with RAGAS metrics
 *    e. Collect timing and results
 * 3. Aggregate results and generate reports
 *
 * @param llmClient LLM client for answer generation and evaluation
 * @param embeddingClient Default embedding client
 * @param datasetManager Dataset loading manager
 * @param options Runner configuration options
 *
 * @example
 * {{{
 * val runner = BenchmarkRunner(llmClient, embeddingClient, resolveEmbeddingProvider)
 * val suite = BenchmarkSuite.chunkingSuite("data/datasets/ragbench/test.jsonl")
 * val results = runner.runSuite(suite)
 * println(BenchmarkReport.console(results))
 * }}}
 */
class BenchmarkRunner(
  llmClient: LLMClient,
  embeddingClient: EmbeddingClient,
  resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig],
  datasetManager: DatasetManager = DatasetManager(),
  val options: BenchmarkRunnerOptions = BenchmarkRunnerOptions()
) {

  private val logger = LoggerFactory.getLogger(getClass)

  private def logVerbose(msg: => String): Unit =
    if (options.verbose) logger.info(msg)

  /**
   * Run a complete benchmark suite.
   *
   * @param suite The benchmark suite to run
   * @return Aggregated results for all experiments
   */
  def runSuite(suite: BenchmarkSuite): Result[BenchmarkResults] = {
    logger.info(s"Starting benchmark suite: ${suite.name}")
    logger.info(s"Running ${suite.experiments.size} experiments")

    val startTime = System.currentTimeMillis()

    // Load dataset
    val datasetResult = datasetManager.load(suite.datasetPath, suite.subsetSize, suite.seed)

    datasetResult.flatMap { dataset =>
      logger.info(s"Loaded ${dataset.samples.size} samples from ${suite.datasetPath}")
      logVerbose(s"Dataset contains ${dataset.samples.flatMap(_.contexts).size} context passages")

      // Run each experiment
      val results = suite.experiments.map { config =>
        logger.info(s"Running experiment: ${config.name}")
        logVerbose(s"  Config: ${config.fullDescription}")
        runExperiment(config, dataset) match {
          case Right(result) =>
            logger.info(f"  RAGAS Score: ${result.ragasScore}%.3f")
            result
          case Left(error) =>
            logger.warn(s"  Experiment failed: ${error.message}")
            ExperimentResult.failed(config, error.message)
        }
      }

      val endTime = System.currentTimeMillis()

      Right(
        BenchmarkResults(
          suite = suite,
          results = results,
          startTime = startTime,
          endTime = endTime
        )
      )
    }
  }

  /**
   * Run a single experiment.
   *
   * @param config Experiment configuration
   * @param dataset Evaluation dataset
   * @return Experiment result
   */
  def runExperiment(
    config: RAGExperimentConfig,
    dataset: TestDataset
  ): Result[ExperimentResult] = {
    val timings = scala.collection.mutable.ArrayBuffer[TimingInfo]()

    // Create embedding client for this config (may differ from default)
    val experimentEmbeddings = if (config.embeddingConfig == EmbeddingConfig.default) {
      Right(embeddingClient)
    } else {
      RAGPipeline.createEmbeddingClient(config.embeddingConfig, resolveEmbeddingProvider)
    }

    experimentEmbeddings.flatMap { embedClient =>
      // Create pipeline
      RAGPipeline.fromConfig(config, llmClient, embedClient).flatMap { pipeline =>
        val result = runExperimentWithPipeline(config, dataset, pipeline, embedClient, timings)
        pipeline.close()
        result
      }
    }
  }

  private def runExperimentWithPipeline(
    config: RAGExperimentConfig,
    dataset: TestDataset,
    pipeline: RAGPipeline,
    embedClient: EmbeddingClient,
    timings: scala.collection.mutable.ArrayBuffer[TimingInfo]
  ): Result[ExperimentResult] = {
    // Extract documents from dataset samples for indexing
    val documents = extractDocuments(dataset)

    // Index documents
    val indexStart = System.currentTimeMillis()
    pipeline.indexDocuments(documents) match {
      case Left(error) =>
        Left(error)
      case Right(chunkCount) =>
        val indexTiming = TimingInfo("indexing", System.currentTimeMillis() - indexStart, documents.size)
        timings += indexTiming

        // Generate answers for each sample
        val (evalSamples, searchTiming) = TimingInfo.measure("search", dataset.samples.size) {
          dataset.samples.flatMap { sample =>
            pipeline.answer(sample.question, Some(config.topK)) match {
              case Right(ragAnswer) =>
                Some(
                  EvalSample(
                    question = sample.question,
                    answer = ragAnswer.answer,
                    contexts = ragAnswer.contexts,
                    groundTruth = sample.groundTruth,
                    metadata = sample.metadata
                  )
                )
              case Left(_) => None
            }
          }
        }
        timings += searchTiming

        // Run RAGAS evaluation
        val embeddingModelConfig = EmbeddingModelConfig(
          config.embeddingConfig.model,
          config.embeddingConfig.dimensions
        )

        val evaluator = RAGASEvaluator(llmClient, embedClient, embeddingModelConfig)

        val (evalResult, evalTiming) = TimingInfo.measure("evaluation", evalSamples.size) {
          evaluator.evaluateBatch(evalSamples)
        }
        timings += evalTiming

        evalResult.map { summary =>
          ExperimentResult.success(
            config = config,
            evalSummary = summary,
            timings = timings.toSeq,
            documentCount = documents.size,
            chunkCount = chunkCount,
            queryCount = evalSamples.size
          )
        }
    }
  }

  /**
   * Run a quick validation with minimal samples.
   *
   * @param config Experiment configuration
   * @param datasetPath Path to dataset
   * @param sampleCount Number of samples to test
   * @return Experiment result
   */
  def quickTest(
    config: RAGExperimentConfig,
    datasetPath: String,
    sampleCount: Int = 5
  ): Result[ExperimentResult] =
    datasetManager.load(datasetPath, Some(sampleCount)).flatMap(dataset => runExperiment(config, dataset))

  /**
   * Compare two configurations head-to-head.
   *
   * @param config1 First configuration
   * @param config2 Second configuration
   * @param datasetPath Path to dataset
   * @param sampleCount Number of samples (optional)
   * @return Comparison result
   */
  def compareConfigs(
    config1: RAGExperimentConfig,
    config2: RAGExperimentConfig,
    datasetPath: String,
    sampleCount: Option[Int] = None
  ): Result[ExperimentComparison] =
    datasetManager.load(datasetPath, sampleCount).flatMap { dataset =>
      for {
        result1 <- runExperiment(config1, dataset)
        result2 <- runExperiment(config2, dataset)
      } yield ExperimentComparison(result1, result2)
    }

  /**
   * Extract unique documents from dataset samples.
   */
  private def extractDocuments(
    dataset: TestDataset
  ): Seq[(String, String, Map[String, String])] = {
    // Collect unique contexts across all samples
    val documentMap = scala.collection.mutable.LinkedHashMap[String, (String, Map[String, String])]()

    dataset.samples.zipWithIndex.foreach { case (sample, sampleIdx) =>
      sample.contexts.zipWithIndex.foreach { case (context, contextIdx) =>
        val docId = s"doc-$sampleIdx-$contextIdx"
        if (!documentMap.contains(context)) {
          documentMap(context) = (docId, Map("sampleIndex" -> sampleIdx.toString))
        }
      }
    }

    documentMap.map { case (content, (id, metadata)) =>
      (id, content, metadata)
    }.toSeq
  }
}

/**
 * Configuration options for the benchmark runner.
 *
 * @param verbose Enable verbose logging
 * @param parallelExperiments Run experiments in parallel (not yet implemented)
 * @param saveIntermediateResults Save results after each experiment
 * @param outputDir Directory for saving results
 */
final case class BenchmarkRunnerOptions(
  verbose: Boolean = false,
  parallelExperiments: Boolean = false,
  saveIntermediateResults: Boolean = false,
  outputDir: String = "data/results"
)

object BenchmarkRunner {

  /**
   * Create a benchmark runner with specific clients.
   *
   * @param llmClient LLM client
   * @param embeddingClient Embedding client
   * @param options Runner options
   * @return BenchmarkRunner
   */
  def apply(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig],
    options: BenchmarkRunnerOptions = BenchmarkRunnerOptions()
  ): BenchmarkRunner =
    new BenchmarkRunner(llmClient, embeddingClient, resolveEmbeddingProvider, options = options)
}
